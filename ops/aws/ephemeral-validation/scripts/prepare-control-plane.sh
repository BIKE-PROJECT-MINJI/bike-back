#!/usr/bin/env bash
set -euo pipefail

readonly ALLOW_AWS_APPLY="${ALLOW_AWS_APPLY:-NO}"
[[ "$ALLOW_AWS_APPLY" == "YES" ]] || {
  printf 'set ALLOW_AWS_APPLY=YES after preflight passes\n' >&2
  exit 1
}

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"
[[ -f "$TFVARS" ]] || {
  printf 'run preflight.sh first\n' >&2
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
readonly CLEANUP_START_AT="$(read_tfvar cleanup_start_at)"
readonly CLEANUP_SCHEDULE="gaja-${RUN_ID}-cleanup"
readonly TEMP_DIR="$(mktemp -d /dev/shm/gaja-control-plane.XXXXXX)"
trap 'rm -rf "$TEMP_DIR"' EXIT

# The cleanup guard must exist before the first chargeable artifact is uploaded.
terraform -chdir="$STACK_DIR" init -input=false
terraform -chdir="$STACK_DIR" apply \
  -input=false \
  -auto-approve \
  -target=aws_scheduler_schedule.cleanup \
  -target=aws_lambda_permission.scheduler

schedule_state="$(aws scheduler get-schedule \
  --region "$AWS_REGION" \
  --name "$CLEANUP_SCHEDULE" \
  --query State \
  --output text)"
schedule_expression="$(aws scheduler get-schedule \
  --region "$AWS_REGION" \
  --name "$CLEANUP_SCHEDULE" \
  --query ScheduleExpression \
  --output text)"
[[ "$schedule_state" == "ENABLED" && "$schedule_expression" == "at($CLEANUP_START_AT)" ]] || {
  printf 'cleanup scheduler verification failed: state=%s expression=%s\n' \
    "$schedule_state" "$schedule_expression" >&2
  exit 1
}

if ! aws s3api head-bucket --bucket "$ARTIFACT_BUCKET" 2>/dev/null; then
  aws s3api create-bucket \
    --bucket "$ARTIFACT_BUCKET" \
    --region "$AWS_REGION" \
    --create-bucket-configuration "LocationConstraint=$AWS_REGION" >/dev/null
fi

aws s3api put-public-access-block \
  --bucket "$ARTIFACT_BUCKET" \
  --public-access-block-configuration \
    BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption \
  --bucket "$ARTIFACT_BUCKET" \
  --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"},"BucketKeyEnabled":true}]}'
aws s3api put-bucket-tagging \
  --bucket "$ARTIFACT_BUCKET" \
  --tagging "TagSet=[{Key=Project,Value=GAJA},{Key=Purpose,Value=ephemeral-validation},{Key=RunId,Value=$RUN_ID}]"
aws s3api put-bucket-lifecycle-configuration \
  --bucket "$ARTIFACT_BUCKET" \
  --lifecycle-configuration \
    '{"Rules":[{"ID":"expire-run-artifacts","Status":"Enabled","Filter":{"Prefix":"runs/"},"Expiration":{"Days":1},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'

put_secure_parameter() {
  local parameter_suffix="$1"
  local secret_file="$2"
  local parameter_name="${SECRET_PREFIX}${parameter_suffix}"
  local expected_description="GAJA ephemeral validation run ${RUN_ID}"
  local request_file="$TEMP_DIR/${parameter_suffix}.json"
  local parameter_type
  local parameter_description
  if aws ssm get-parameter \
    --region "$AWS_REGION" \
    --name "$parameter_name" >/dev/null 2>&1; then
    parameter_type="$(aws ssm get-parameter \
      --region "$AWS_REGION" \
      --name "$parameter_name" \
      --query Parameter.Type \
      --output text)"
    parameter_description="$(aws ssm describe-parameters \
      --region "$AWS_REGION" \
      --parameter-filters "Key=Name,Option=Equals,Values=$parameter_name" \
      --query 'Parameters[0].Description' \
      --output text)"
    [[ "$parameter_type" == 'SecureString' \
      && "$parameter_description" == "$expected_description" ]] || {
      printf 'existing parameter ownership mismatch: %s type=%s\n' \
        "$parameter_name" "$parameter_type" >&2
      exit 1
    }
    return 0
  fi
  python3 - "$request_file" "$parameter_name" "$secret_file" "$RUN_ID" <<'PY'
import json
import sys

target, name, secret_path, run_id = sys.argv[1:]
with open(secret_path, encoding="utf-8") as source:
    value = source.read().strip()
request = {
    "Name": name,
    "Value": value,
    "Type": "SecureString",
    "Tier": "Standard",
    "Description": f"GAJA ephemeral validation run {run_id}",
    "Tags": [
        {"Key": "Project", "Value": "GAJA"},
        {"Key": "Purpose", "Value": "ephemeral-validation"},
        {"Key": "RunId", "Value": run_id},
    ],
}
with open(target, "w", encoding="utf-8") as output:
    json.dump(request, output, ensure_ascii=True)
PY
  chmod 0600 "$request_file"
  aws ssm put-parameter \
    --region "$AWS_REGION" \
    --cli-input-json "file://$request_file" >/dev/null
}

for secret_name in db-password redis-password jwt-secret grafana-password; do
  openssl rand -base64 48 | tr -d '\n' >"$TEMP_DIR/$secret_name"
  chmod 0600 "$TEMP_DIR/$secret_name"
  put_secure_parameter "$secret_name" "$TEMP_DIR/$secret_name"
done

printf 'control_plane_ready run_id=%s cleanup=%s bucket=%s secret_prefix=%s\n' \
  "$RUN_ID" "$CLEANUP_START_AT" "$ARTIFACT_BUCKET" "$SECRET_PREFIX"
