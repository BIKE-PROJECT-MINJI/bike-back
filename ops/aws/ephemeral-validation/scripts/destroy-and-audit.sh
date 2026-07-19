#!/usr/bin/env bash
set -euo pipefail

readonly ALLOW_AWS_DESTROY="${ALLOW_AWS_DESTROY:-NO}"
[[ "$ALLOW_AWS_DESTROY" == "YES" ]] || {
  printf 'set ALLOW_AWS_DESTROY=YES to destroy this run\n' >&2
  exit 1
}

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/teardown"
readonly DESTROY_AUTHORIZATION_FILE="$STACK_DIR/.artifacts/destroy-authorized"
trap 'rm -f "$DESTROY_AUTHORIZATION_FILE"' EXIT

for command in aws python3 terraform; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
[[ -f "$TFVARS" ]] || {
  printf 'terraform.auto.tfvars.json is required for scoped cleanup\n' >&2
  exit 1
}

read_tfvar() {
  python3 - "$TFVARS" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)
print(payload[sys.argv[2]])
PY
}

readonly AWS_REGION="$(read_tfvar aws_region)"
readonly RUN_ID="$(read_tfvar run_id)"
readonly ARTIFACT_BUCKET="$(read_tfvar artifact_bucket_name)"
readonly SECRET_PREFIX="$(read_tfvar secret_parameter_prefix)"
install -d -m 0700 "$EVIDENCE_DIR"

terraform -chdir="$STACK_DIR" output -json >"$EVIDENCE_DIR/pre-destroy-outputs.json" 2>/dev/null || true
terraform -chdir="$STACK_DIR" show -json >"$EVIDENCE_DIR/pre-destroy-state.json" 2>/dev/null || true

bucket_state() {
  local error_file="$EVIDENCE_DIR/head-bucket-error.txt"
  if aws s3api head-bucket \
    --region "$AWS_REGION" \
    --bucket "$ARTIFACT_BUCKET" >/dev/null 2>"$error_file"; then
    rm -f "$error_file"
    printf 'exists'
    return 0
  fi

  local error_text
  error_text="$(<"$error_file")"
  if [[ "$error_text" == *'(404)'* \
    || "$error_text" == *'NoSuchBucket'* \
    || "$error_text" == *'Not Found'* ]]; then
    rm -f "$error_file"
    printf 'missing'
    return 0
  fi

  printf 'head-bucket returned unexpected error; cleanup guard remains enabled\n%s\n' \
    "$error_text" >&2
  return 1
}

# Keep the scheduled cleanup guard alive if object or bucket deletion fails.
current_bucket_state="$(bucket_state)"
if [[ "$current_bucket_state" == 'exists' ]]; then
  aws s3 rm "s3://$ARTIFACT_BUCKET" --recursive --only-show-errors
  aws s3api delete-bucket --region "$AWS_REGION" --bucket "$ARTIFACT_BUCKET"
fi
if [[ "$(bucket_state)" != 'missing' ]]; then
  printf 'artifact bucket still exists; cleanup guard remains enabled\n' >&2
  exit 1
fi

# Destroy mode is an explicit CLI-only bypass because the external gate's
# artifacts were deliberately removed while the cleanup guard was active.
printf '%s\n' "$RUN_ID" >"$DESTROY_AUTHORIZATION_FILE"
chmod 0600 "$DESTROY_AUTHORIZATION_FILE"
terraform -chdir="$STACK_DIR" destroy \
  -input=false \
  -auto-approve \
  -var=destroy_mode=true

mapfile -t parameter_names < <(aws ssm get-parameters-by-path \
  --region "$AWS_REGION" \
  --path "$SECRET_PREFIX" \
  --recursive \
  --query 'Parameters[].Name' \
  --output text 2>/dev/null | tr '\t' '\n' | sed '/^$/d')
if ((${#parameter_names[@]} > 0)); then
  for ((start = 0; start < ${#parameter_names[@]}; start += 10)); do
    aws ssm delete-parameters \
      --region "$AWS_REGION" \
      --names "${parameter_names[@]:start:10}" >/dev/null
  done
fi

count_elbv2_tagged() {
  local resource_kind="$1"
  local describe_command="describe-${resource_kind}"
  local query
  if [[ "$resource_kind" == "load-balancers" ]]; then
    query='LoadBalancers[].LoadBalancerArn'
  else
    query='TargetGroups[].TargetGroupArn'
  fi
  local count=0
  local arn
  while IFS= read -r arn; do
    [[ -n "$arn" ]] || continue
    local value
    value="$(aws elbv2 describe-tags \
      --region "$AWS_REGION" \
      --resource-arns "$arn" \
      --query "TagDescriptions[0].Tags[?Key=='RunId'].Value | [0]" \
      --output text)"
    [[ "$value" == "$RUN_ID" ]] && count=$((count + 1))
  done < <(aws elbv2 "$describe_command" --region "$AWS_REGION" --query "$query" --output text | tr '\t' '\n')
  printf '%s' "$count"
}

declare -A residuals
residuals[instances]="$(aws ec2 describe-instances --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" 'Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down' \
  --query 'length(Reservations[].Instances[])' --output text)"
residuals[volumes]="$(aws ec2 describe-volumes --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(Volumes)' --output text)"
residuals[addresses]="$(aws ec2 describe-addresses --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(Addresses)' --output text)"
residuals[nat_gateways]="$(aws ec2 describe-nat-gateways --region "$AWS_REGION" \
  --filter "Name=tag:RunId,Values=$RUN_ID" --query 'length(NatGateways)' --output text)"
residuals[vpc_endpoints]="$(aws ec2 describe-vpc-endpoints --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(VpcEndpoints)' --output text)"
residuals[vpcs]="$(aws ec2 describe-vpcs --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(Vpcs)' --output text)"
residuals[subnets]="$(aws ec2 describe-subnets --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(Subnets)' --output text)"
residuals[route_tables]="$(aws ec2 describe-route-tables --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(RouteTables)' --output text)"
residuals[internet_gateways]="$(aws ec2 describe-internet-gateways --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(InternetGateways)' --output text)"
residuals[security_groups]="$(aws ec2 describe-security-groups --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(SecurityGroups)' --output text)"
residuals[network_interfaces]="$(aws ec2 describe-network-interfaces --region "$AWS_REGION" \
  --filters "Name=tag:RunId,Values=$RUN_ID" --query 'length(NetworkInterfaces)' --output text)"
residuals[load_balancers]="$(count_elbv2_tagged load-balancers)"
residuals[target_groups]="$(count_elbv2_tagged target-groups)"
residuals[parameters]="$(aws ssm get-parameters-by-path --region "$AWS_REGION" \
  --path "$SECRET_PREFIX" --recursive --query 'length(Parameters)' --output text)"
residuals[schedules]="$(aws scheduler list-schedules --region "$AWS_REGION" \
  --name-prefix "gaja-$RUN_ID" --query 'length(Schedules)' --output text)"
residuals[functions]="$(aws lambda list-functions --region "$AWS_REGION" \
  --query "length(Functions[?starts_with(FunctionName, 'gaja-$RUN_ID')])" --output text)"
residuals[log_groups]="$(aws logs describe-log-groups --region "$AWS_REGION" \
  --log-group-name-prefix "/gaja/ephemeral/$RUN_ID" --query 'length(logGroups)' --output text)"
residuals[cleanup_log_groups]="$(aws logs describe-log-groups --region "$AWS_REGION" \
  --log-group-name-prefix "/aws/lambda/gaja-$RUN_ID" --query 'length(logGroups)' --output text)"
residuals[iam_roles]="$(aws iam list-roles --path-prefix / \
  --query "length(Roles[?starts_with(RoleName, 'gaja-$RUN_ID')])" --output text)"
residuals[instance_profiles]="$(aws iam list-instance-profiles --path-prefix / \
  --query "length(InstanceProfiles[?starts_with(InstanceProfileName, 'gaja-$RUN_ID')])" --output text)"
final_bucket_state="$(bucket_state)"
if [[ "$final_bucket_state" == 'exists' ]]; then
  residuals[artifact_bucket]=1
else
  residuals[artifact_bucket]=0
fi

residual_total=0
mapfile -t sorted_resources < <(printf '%s\n' "${!residuals[@]}" | sort)
for resource in "${sorted_resources[@]}"; do
  residual_total=$((residual_total + residuals[$resource]))
done

{
  printf '{\n  "run_id": "%s",\n  "resources": {\n' "$RUN_ID"
  first=true
  for resource in "${sorted_resources[@]}"; do
    $first || printf ',\n'
    first=false
    printf '    "%s": %s' "$resource" "${residuals[$resource]}"
  done
  printf '\n  },\n  "residual_total": %s\n}\n' "$residual_total"
} >"$EVIDENCE_DIR/residual-audit.json"
cat "$EVIDENCE_DIR/residual-audit.json"

if ((residual_total != 0)); then
  printf 'residual audit failed for %s: %s resources remain\n' "$RUN_ID" "$residual_total" >&2
  exit 1
fi

printf 'destroy=PASS residual_total=0 evidence=%s\n' "$EVIDENCE_DIR"
