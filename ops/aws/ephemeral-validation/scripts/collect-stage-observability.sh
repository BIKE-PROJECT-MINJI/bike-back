#!/usr/bin/env bash
set -euo pipefail

readonly STAGE="${1:?pass stage}"
readonly PHASE="${2:?pass before or after}"
[[ "$PHASE" == 'before' || "$PHASE" == 'after' ]] || {
  printf 'phase must be before or after\n' >&2
  exit 2
}

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"
# shellcheck source=ssm-command-evidence.sh
source "$SCRIPT_DIR/ssm-command-evidence.sh"

read_tfvar() {
  python3 - "$TFVARS" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)[sys.argv[2]])
PY
}

readonly AWS_REGION="$(read_tfvar aws_region)"
readonly RUN_ID="$(read_tfvar run_id)"
readonly APP_COUNT="$(read_tfvar app_count)"
readonly ARTIFACT_BUCKET="$(read_tfvar artifact_bucket_name)"
readonly TEST_ID="${RUN_ID}-${STAGE}-observability-${PHASE}"
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/$RUN_ID/observability/$STAGE/$PHASE"
readonly OBSERVABILITY_INSTANCE_ID="$(terraform -chdir="$STACK_DIR" output -json instance_ids \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["singleton"]["observability"])')"

rm -rf "$EVIDENCE_DIR"
install -d -m 0700 "$EVIDENCE_DIR"
readonly REMOTE_COMMAND="set -euo pipefail
evidence_dir=/opt/gaja-run/evidence/${TEST_ID}
rm -rf \"\$evidence_dir\"
install -d -m 0700 \"\$evidence_dir\"
curl -fsS --max-time 10 http://127.0.0.1:9090/api/v1/targets > \"\$evidence_dir/prometheus-targets.json\"
curl -fsS --max-time 10 http://10.88.10.20:18081/actuator/prometheus > \"\$evidence_dir/app-1-prometheus.txt\"
if [[ '${APP_COUNT}' == '2' ]]; then
  curl -fsS --max-time 10 http://10.88.11.20:18081/actuator/prometheus > \"\$evidence_dir/app-2-prometheus.txt\"
fi
docker stats --no-stream --format '{{json .}}' > \"\$evidence_dir/observability-docker-stats.jsonl\"
(cd \"\$evidence_dir\" && find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 \\
  | sort -z | xargs -0 -r sha256sum > SHA256SUMS)
aws s3 sync \"\$evidence_dir/\" s3://${ARTIFACT_BUCKET}/runs/${RUN_ID}/observability/${STAGE}/${PHASE}/ \\
  --sse AES256 --only-show-errors"

python3 - \
  "$EVIDENCE_DIR/request.json" \
  "$OBSERVABILITY_INSTANCE_ID" \
  "$REMOTE_COMMAND" \
  "$ARTIFACT_BUCKET" \
  "$RUN_ID" \
  "$TEST_ID" <<'PY'
import json
import sys

target, instance_id, command, bucket, run_id, test_id = sys.argv[1:]
payload = {
    "DocumentName": "AWS-RunShellScript",
    "InstanceIds": [instance_id],
    "Comment": f"GAJA observability snapshot {test_id}",
    "Parameters": {"commands": [command]},
    "TimeoutSeconds": 120,
    "OutputS3BucketName": bucket,
    "OutputS3KeyPrefix": f"runs/{run_id}/ssm-output/{test_id}",
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
persist_ssm_command_id "$EVIDENCE_DIR" "$COMMAND_ID"
wait_for_ssm_command_with_evidence \
  "$AWS_REGION" "$COMMAND_ID" "$OBSERVABILITY_INSTANCE_ID" \
  "$EVIDENCE_DIR/command-result.json" 24 5
aws s3 sync \
  "s3://$ARTIFACT_BUCKET/runs/$RUN_ID/observability/$STAGE/$PHASE/" \
  "$EVIDENCE_DIR/" \
  --region "$AWS_REGION" \
  --only-show-errors
bash "$SCRIPT_DIR/scan-k6-evidence-redaction.sh" \
  "$EVIDENCE_DIR" \
  "$EVIDENCE_DIR/redaction-scan.json"
rm -f "$EVIDENCE_DIR/request.json"
printf 'observability_snapshot=PASS stage=%s phase=%s evidence=%s\n' \
  "$STAGE" "$PHASE" "$EVIDENCE_DIR"
