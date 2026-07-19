#!/usr/bin/env bash
set -euo pipefail

readonly AWS_REGION="${AWS_REGION:-ap-northeast-2}"
readonly DOMAIN_NAME="${DOMAIN_NAME:-api.gajabike.shop}"
readonly RUN_ID="${RUN_ID:?set RUN_ID}"
readonly ACM_ARN="${ACM_ARN:?set ACM_ARN}"
readonly APP_COUNT="${APP_COUNT:-1}"
readonly TTL_MINUTES=180
readonly CLEANUP_START_MINUTES=165
readonly COST_HEADROOM=1.20
readonly COST_LIMIT_USD=3
readonly ALB_HOURLY_CEILING_USD=0.04
readonly ALB_LCU_HOURLY_CEILING_USD=0.04
readonly INTERFACE_ENDPOINT_HOURLY_CEILING_USD=0.02
readonly INTERFACE_ENDPOINT_COUNT=3
readonly PUBLIC_IPV4_HOURLY_CEILING_USD=0.005
readonly PUBLIC_IPV4_COUNT=2
readonly EBS_GIB_MONTH_CEILING_USD=0.15
readonly ROOT_VOLUME_SIZE_GIB=30
readonly LOG_INGESTION_CEILING_USD=0.75
readonly S3_LAMBDA_SCHEDULER_CEILING_USD=0.15
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

for command in aws bc openssl python3 terraform; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

[[ "$APP_COUNT" == "1" || "$APP_COUNT" == "2" ]] || {
  printf 'APP_COUNT must be 1 or 2\n' >&2
  exit 1
}

mapfile -t ECS_AMI_METADATA < <(
  aws ec2 describe-images \
    --region "$AWS_REGION" \
    --owners amazon \
    --filters \
      'Name=name,Values=al2023-ami-ecs-hvm-*-x86_64' \
      'Name=state,Values=available' \
    --query 'sort_by(Images, &CreationDate)[-1]' \
    --output json | python3 -c '
import json
import sys

image = json.load(sys.stdin)
if not isinstance(image, dict) or not image:
    raise SystemExit("latest ECS-optimized AL2023 AMI not found")
image_id = image.get("ImageId")
image_name = image.get("Name")
root_device = image.get("RootDeviceName")
mappings = image.get("BlockDeviceMappings", [])
root_mapping = next(
    (mapping for mapping in mappings if mapping.get("DeviceName") == root_device),
    None,
)
volume_size = (root_mapping or {}).get("Ebs", {}).get("VolumeSize")
if not isinstance(image_id, str) or not image_id.startswith("ami-"):
    raise SystemExit("resolved ECS AMI has no valid ImageId")
if not isinstance(image_name, str) or not image_name:
    raise SystemExit("resolved ECS AMI has no Name")
if not isinstance(volume_size, int) or volume_size <= 0:
    raise SystemExit("resolved ECS AMI has no positive root volume size")
print(image_id)
print(image_name)
print(volume_size)
'
)
[[ "${#ECS_AMI_METADATA[@]}" == "3" ]] || {
  printf 'failed to resolve ECS AMI metadata\n' >&2
  exit 1
}
readonly ECS_AMI_ID="${ECS_AMI_METADATA[0]}"
readonly ECS_AMI_NAME="${ECS_AMI_METADATA[1]}"
readonly ECS_AMI_ROOT_VOLUME_GIB="${ECS_AMI_METADATA[2]}"
((ROOT_VOLUME_SIZE_GIB >= ECS_AMI_ROOT_VOLUME_GIB)) || {
  printf 'root volume gate failed: configured=%sGiB ami_minimum=%sGiB ami=%s\n' \
    "$ROOT_VOLUME_SIZE_GIB" "$ECS_AMI_ROOT_VOLUME_GIB" "$ECS_AMI_ID" >&2
  exit 1
}

readonly ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
readonly ARTIFACT_BUCKET="gaja-ev-${ACCOUNT_ID}-${RUN_ID}"
readonly SECRET_PREFIX="/gaja/ephemeral/${RUN_ID}/"
readonly CLEANUP_START_AT="$(date -u -d "+${CLEANUP_START_MINUTES} minutes" +%Y-%m-%dT%H:%M:%S)"

aws rds describe-db-instances --region "$AWS_REGION" --max-records 20 >/dev/null
aws elasticache describe-cache-clusters --region "$AWS_REGION" --max-records 20 >/dev/null
aws lambda list-functions --region "$AWS_REGION" --max-items 1 >/dev/null
aws scheduler list-schedules --region "$AWS_REGION" --max-results 1 >/dev/null
aws ssm describe-parameters --region "$AWS_REGION" --max-results 1 >/dev/null

readonly CERTIFICATE_STATUS="$(aws acm describe-certificate \
  --region "$AWS_REGION" \
  --certificate-arn "$ACM_ARN" \
  --query 'Certificate.Status' \
  --output text)"
readonly CERTIFICATE_DOMAIN="$(aws acm describe-certificate \
  --region "$AWS_REGION" \
  --certificate-arn "$ACM_ARN" \
  --query 'Certificate.DomainName' \
  --output text)"
[[ "$CERTIFICATE_STATUS" == "ISSUED" && "$CERTIFICATE_DOMAIN" == "$DOMAIN_NAME" ]] || {
  printf 'ACM certificate must be ISSUED and match %s\n' "$DOMAIN_NAME" >&2
  exit 1
}

price_ec2_hourly() {
  local instance_type="$1"
  aws pricing get-products \
    --region us-east-1 \
    --service-code AmazonEC2 \
    --filters \
      "Type=TERM_MATCH,Field=location,Value=Asia Pacific (Seoul)" \
      "Type=TERM_MATCH,Field=instanceType,Value=${instance_type}" \
      "Type=TERM_MATCH,Field=operatingSystem,Value=Linux" \
      "Type=TERM_MATCH,Field=tenancy,Value=Shared" \
      "Type=TERM_MATCH,Field=preInstalledSw,Value=NA" \
      "Type=TERM_MATCH,Field=capacitystatus,Value=Used" \
    --max-results 10 \
    --query 'PriceList' \
    --output json | python3 -c '
import json, sys
entries = json.load(sys.stdin)
for encoded in entries:
    product = json.loads(encoded)
    for term in product["terms"]["OnDemand"].values():
        for dimension in term["priceDimensions"].values():
            price = dimension["pricePerUnit"].get("USD")
            if price is not None and float(price) > 0:
                print(price)
                raise SystemExit(0)
raise SystemExit("EC2 price not found")
'
}

readonly T3_SMALL_HOURLY="$(price_ec2_hourly t3.small)"
readonly T3_MICRO_HOURLY="$(price_ec2_hourly t3.micro)"
readonly SMALL_COUNT="$((APP_COUNT + 3))"
readonly MICRO_COUNT=2
readonly HOURS="$((TTL_MINUTES / 60))"
readonly ROOT_VOLUME_GIB="$((ROOT_VOLUME_SIZE_GIB * (APP_COUNT + 5)))"
readonly EC2_COST="$(echo "scale=6; (($T3_SMALL_HOURLY * $SMALL_COUNT) + ($T3_MICRO_HOURLY * $MICRO_COUNT)) * $HOURS" | bc)"
readonly ALB_COST="$(echo "scale=6; ($ALB_HOURLY_CEILING_USD + $ALB_LCU_HOURLY_CEILING_USD) * $HOURS" | bc)"
readonly INTERFACE_ENDPOINT_COST="$(echo "scale=6; $INTERFACE_ENDPOINT_HOURLY_CEILING_USD * $INTERFACE_ENDPOINT_COUNT * $HOURS" | bc)"
readonly PUBLIC_IPV4_COST="$(echo "scale=6; $PUBLIC_IPV4_HOURLY_CEILING_USD * $PUBLIC_IPV4_COUNT * $HOURS" | bc)"
readonly EBS_COST="$(echo "scale=6; $EBS_GIB_MONTH_CEILING_USD * $ROOT_VOLUME_GIB * $HOURS / 730" | bc)"
readonly ESTIMATED_BASE="$(echo "scale=6; $EC2_COST + $ALB_COST + $INTERFACE_ENDPOINT_COST + $PUBLIC_IPV4_COST + $EBS_COST + $LOG_INGESTION_CEILING_USD + $S3_LAMBDA_SCHEDULER_CEILING_USD" | bc)"
readonly ESTIMATED_WITH_HEADROOM="$(echo "scale=6; $ESTIMATED_BASE * $COST_HEADROOM" | bc)"

if [[ "$(echo "$ESTIMATED_WITH_HEADROOM >= $COST_LIMIT_USD" | bc)" == "1" ]]; then
  printf 'cost gate failed: USD %s with headroom is not below USD %s\n' \
    "$ESTIMATED_WITH_HEADROOM" "$COST_LIMIT_USD" >&2
  exit 1
fi

python3 - "$STACK_DIR/terraform.auto.tfvars.json" <<PY
import json
import sys

target = sys.argv[1]
payload = {
    "aws_region": "$AWS_REGION",
    "run_id": "$RUN_ID",
    "domain_name": "$DOMAIN_NAME",
    "existing_acm_certificate_arn": "$ACM_ARN",
    "ecs_optimized_ami_id": "$ECS_AMI_ID",
    "artifact_bucket_name": "$ARTIFACT_BUCKET",
    "secret_parameter_prefix": "$SECRET_PREFIX",
    "cleanup_start_at": "$CLEANUP_START_AT",
    "app_count": int("$APP_COUNT"),
    "attach_app_targets": False,
    "ttl_minutes": 180,
    "cost_limit_usd": 3,
    "root_volume_sizes_gib": {
        "app": $ROOT_VOLUME_SIZE_GIB,
        "db": $ROOT_VOLUME_SIZE_GIB,
        "redis": $ROOT_VOLUME_SIZE_GIB,
        "graphhopper": $ROOT_VOLUME_SIZE_GIB,
        "load": $ROOT_VOLUME_SIZE_GIB,
        "observability": $ROOT_VOLUME_SIZE_GIB,
    },
}
with open(target, "w", encoding="utf-8") as output:
    json.dump(payload, output, ensure_ascii=True, indent=2)
    output.write("\n")
PY

printf 'run_id=%s\n' "$RUN_ID"
printf 'account=%s region=%s app_count=%s\n' "$ACCOUNT_ID" "$AWS_REGION" "$APP_COUNT"
printf 'cleanup_start=%sZ hard_ttl=%sm\n' "$CLEANUP_START_AT" "$TTL_MINUTES"
printf 'estimated_base_usd=%s estimated_with_20pct_headroom_usd=%s limit_usd=%s\n' \
  "$ESTIMATED_BASE" "$ESTIMATED_WITH_HEADROOM" "$COST_LIMIT_USD"
printf 'cost_breakdown ec2=%s alb_lcu=%s interface_endpoints=%s public_ipv4=%s ebs=%s logs=%s control_plane=%s\n' \
  "$EC2_COST" "$ALB_COST" "$INTERFACE_ENDPOINT_COST" "$PUBLIC_IPV4_COST" "$EBS_COST" \
  "$LOG_INGESTION_CEILING_USD" "$S3_LAMBDA_SCHEDULER_CEILING_USD"
printf 'artifact_bucket=%s secret_prefix=%s\n' "$ARTIFACT_BUCKET" "$SECRET_PREFIX"
printf 'ecs_ami_id=%s ecs_ami_name=%s ami_root_minimum_gib=%s configured_root_gib=%s total_root_gib=%s\n' \
  "$ECS_AMI_ID" "$ECS_AMI_NAME" "$ECS_AMI_ROOT_VOLUME_GIB" "$ROOT_VOLUME_SIZE_GIB" "$ROOT_VOLUME_GIB"
