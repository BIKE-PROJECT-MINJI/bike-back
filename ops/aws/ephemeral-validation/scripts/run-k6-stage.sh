#!/usr/bin/env bash
set -euo pipefail

readonly STAGE="${STAGE:?set STAGE to smoke, baseline-10, stress-25, burst-50, or ai-25}"
readonly ALLOW_HIGH_VUS="${ALLOW_HIGH_VUS:-NO}"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/k6/$STAGE"

for command in aws python3 terraform; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

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
readonly LOAD_INSTANCE_ID="$(terraform -chdir="$STACK_DIR" output -json instance_ids \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["singleton"]["load"])')"
readonly TEST_ID="${RUN_ID}-${STAGE}"
install -d -m 0700 "$EVIDENCE_DIR"

case "$STAGE" in
  smoke)
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=smoke -e AUTH_AUTO_REGISTER=true -e ERROR_RATE_MAX=0.01'
    ;;
  baseline-10)
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=baseline -e AUTH_AUTO_REGISTER=true -e BASELINE_TOTAL_VUS=10 -e BASELINE_RAMP_UP=20s -e BASELINE_HOLD=60s -e BASELINE_RAMP_DOWN=20s -e ERROR_RATE_MAX=0.01'
    ;;
  stress-25)
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=stress -e AUTH_AUTO_REGISTER=true -e STRESS_TOTAL_VUS=25 -e STRESS_RAMP_UP=20s -e STRESS_HOLD=60s -e STRESS_RAMP_DOWN=20s -e ERROR_RATE_MAX=0.01'
    ;;
  burst-50)
    [[ "$ALLOW_HIGH_VUS" == "YES" ]] || {
      printf 'burst-50 requires ALLOW_HIGH_VUS=YES\n' >&2
      exit 1
    }
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=burst -e AUTH_AUTO_REGISTER=true -e BURST_WRITE_VUS=20 -e BURST_INRIDE_VUS=20 -e BURST_PRERIDE_VUS=10 -e BURST_WRITE_ITERATIONS=40 -e BURST_INRIDE_ITERATIONS=80 -e BURST_PRERIDE_ITERATIONS=20 -e ERROR_RATE_MAX=0.01'
    ;;
  ai-25)
    readonly K6_SCRIPT='/scripts/ai-route-graphhopper-100-users.js'
    readonly K6_ENV='-e AI_ROUTE_VUS=5 -e COURSE_MAP_READ_VUS=8 -e COURSE_FOLLOW_VUS=5 -e FREE_RIDE_VUS=4 -e RIDE_FINALIZATION_VUS=3 -e AI_ROUTE_ITERATIONS_PER_VU=1 -e RUN_DURATION=90s -e PROVIDER_MODE=self-hosted -e ERROR_RATE_MAX=0.01'
    ;;
  *)
    printf 'unsupported STAGE: %s\n' "$STAGE" >&2
    exit 1
    ;;
esac

readonly REMOTE_COMMAND="set -euo pipefail
source /opt/gaja-run/role/role.env
install -d -m 0770 -o 12345 -g 12345 /opt/gaja-run/evidence/${TEST_ID}
docker run --rm --network host \\
  -v /opt/gaja-run/k6:/scripts:ro \\
  -v /opt/gaja-run/evidence/${TEST_ID}:/evidence \\
  -e BASE_URL=http://10.88.10.20:8080 \\
  -e TEST_ID=${TEST_ID} \\
  -e SUMMARY_DIR=/evidence \\
  -e SUMMARY_PATH=/evidence/summary.json \\
  ${K6_ENV} \\
  \"\$K6_IMAGE\" run ${K6_SCRIPT}
sha256sum /opt/gaja-run/evidence/${TEST_ID}/* > /opt/gaja-run/evidence/${TEST_ID}/SHA256SUMS
aws s3 sync /opt/gaja-run/evidence/${TEST_ID}/ s3://${ARTIFACT_BUCKET}/runs/${RUN_ID}/evidence/${TEST_ID}/ --sse AES256 --only-show-errors"

python3 - "$EVIDENCE_DIR/request.json" "$LOAD_INSTANCE_ID" "$REMOTE_COMMAND" "$TEST_ID" <<'PY'
import json
import sys

target, instance_id, command, test_id = sys.argv[1:]
payload = {
    "DocumentName": "AWS-RunShellScript",
    "InstanceIds": [instance_id],
    "Comment": f"GAJA k6 stage {test_id}",
    "Parameters": {"commands": [command]},
    "TimeoutSeconds": 900,
}
with open(target, "w", encoding="utf-8") as output:
    json.dump(payload, output, ensure_ascii=True)
PY
chmod 0600 "$EVIDENCE_DIR/request.json"

readonly COMMAND_ID="$(aws ssm send-command \
  --region "$AWS_REGION" \
  --cli-input-json "file://$EVIDENCE_DIR/request.json" \
  --query 'Command.CommandId' \
  --output text)"
for _ in $(seq 1 180); do
  status="$(aws ssm get-command-invocation \
    --region "$AWS_REGION" \
    --command-id "$COMMAND_ID" \
    --instance-id "$LOAD_INSTANCE_ID" \
    --query Status \
    --output text 2>/dev/null || printf 'Pending')"
  case "$status" in
    Success)
      break
      ;;
    Pending|InProgress|Delayed)
      sleep 5
      ;;
    Failed|Cancelled|TimedOut)
      printf 'k6 SSM command failed with status %s\n' "$status" >&2
      exit 1
      ;;
    *)
      printf 'unexpected k6 SSM command status %s\n' "$status" >&2
      exit 1
      ;;
  esac
done
[[ "$status" == "Success" ]] || {
  printf 'k6 SSM command exceeded 900 seconds\n' >&2
  exit 1
}
aws ssm get-command-invocation \
  --region "$AWS_REGION" \
  --command-id "$COMMAND_ID" \
  --instance-id "$LOAD_INSTANCE_ID" \
  --output json >"$EVIDENCE_DIR/command-result.json"

python3 - "$EVIDENCE_DIR/command-result.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    result = json.load(source)
if result.get("Status") != "Success":
    raise SystemExit(f"k6 stage failed: {result.get('StatusDetails')}")
PY

aws s3 sync \
  "s3://$ARTIFACT_BUCKET/runs/$RUN_ID/evidence/$TEST_ID/" \
  "$EVIDENCE_DIR/" \
  --region "$AWS_REGION" \
  --only-show-errors
rm -f "$EVIDENCE_DIR/request.json"
printf 'k6_stage=PASS test_id=%s evidence=%s\n' "$TEST_ID" "$EVIDENCE_DIR"
