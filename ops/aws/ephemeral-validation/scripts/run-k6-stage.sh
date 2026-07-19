#!/usr/bin/env bash
set -euo pipefail

readonly STAGE="${STAGE:?set STAGE to smoke, baseline-10, stress-25, burst-50, or ai-25}"
readonly ALLOW_HIGH_VUS="${ALLOW_HIGH_VUS:-NO}"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"
# shellcheck source=ssm-command-evidence.sh
source "$SCRIPT_DIR/ssm-command-evidence.sh"

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
readonly TEST_ID="${RUN_ID}-${STAGE}"
readonly ATTEMPT_ID="${TEST_ID}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/$RUN_ID/k6/$STAGE/$ATTEMPT_ID"
readonly LOAD_INSTANCE_ID="$(terraform -chdir="$STACK_DIR" output -json instance_ids \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["singleton"]["load"])')"
bash "$SCRIPT_DIR/assert-remaining-ttl.sh" 20
install -d -m 0700 "$EVIDENCE_DIR"

case "$STAGE" in
  smoke)
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=smoke -e AUTH_AUTO_REGISTER=true -e ERROR_RATE_MAX=0.01'
    ;;
  baseline-10)
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=baseline -e AUTH_AUTO_REGISTER=true -e BASELINE_TOTAL_VUS=10 -e BASELINE_RAMP_UP=20s -e BASELINE_HOLD=60s -e BASELINE_RAMP_DOWN=20s -e ADDRESS_SEARCH_EVERY_N_ITERATIONS=20 -e ERROR_RATE_MAX=0.01'
    ;;
  stress-25)
    readonly K6_SCRIPT='/scripts/bike-api.js'
    readonly K6_ENV='-e SCENARIO=stress -e AUTH_AUTO_REGISTER=true -e STRESS_TOTAL_VUS=25 -e STRESS_RAMP_UP=20s -e STRESS_HOLD=60s -e STRESS_RAMP_DOWN=20s -e ADDRESS_SEARCH_EVERY_N_ITERATIONS=20 -e ERROR_RATE_MAX=0.01'
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

readonly REMOTE_COMMAND="set -uo pipefail
source /opt/gaja-run/role/role.env
install -d -m 0770 -o 12345 -g 12345 /opt/gaja-run/evidence/${ATTEMPT_ID}
evidence_dir=/opt/gaja-run/evidence/${ATTEMPT_ID}
finalized=0
finalize_evidence() {
  original_exit_code=\"\${1:-1}\"
  if ((finalized)); then
    return \"\$original_exit_code\"
  fi
  finalized=1
  printf '%s\\n' \"\$original_exit_code\" > \"\$evidence_dir/k6-exit-code.txt\"
  checksum_exit_code=0
  (cd \"\$evidence_dir\" && find . -maxdepth 1 -type f ! -name SHA256SUMS -print0 \\
    | sort -z | xargs -0 -r sha256sum > SHA256SUMS) \\
    || checksum_exit_code=\$?
  printf '%s\\n' \"\$checksum_exit_code\" > \"\$evidence_dir/checksum-exit-code.txt\"
  upload_exit_code=0
  aws s3 sync \"\$evidence_dir/\" s3://${ARTIFACT_BUCKET}/runs/${RUN_ID}/evidence/${TEST_ID}/${ATTEMPT_ID}/ \\
    --sse AES256 --only-show-errors || upload_exit_code=\$?
  printf 'k6_exit_code=%s checksum_exit_code=%s upload_exit_code=%s\\n' \\
    \"\$original_exit_code\" \"\$checksum_exit_code\" \"\$upload_exit_code\"
  if ((original_exit_code != 0)); then
    return \"\$original_exit_code\"
  fi
  if ((checksum_exit_code != 0)); then
    return \"\$checksum_exit_code\"
  fi
  return \"\$upload_exit_code\"
}
on_exit() {
  exit_code=\$?
  trap - EXIT INT TERM
  finalize_evidence \"\$exit_code\"
  final_exit_code=\$?
  exit \"\$final_exit_code\"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
docker run --rm --network host \\
  -v /opt/gaja-run/k6:/scripts:ro \\
  -v /opt/gaja-run/evidence/${ATTEMPT_ID}:/evidence \\
  -e BASE_URL=http://10.88.10.20:8080 \\
  -e TEST_ID=${ATTEMPT_ID} \\
  -e SUMMARY_DIR=/evidence \\
  -e SUMMARY_PATH=/evidence/summary.json \\
  ${K6_ENV} \\
  \"\$K6_IMAGE\" run ${K6_SCRIPT} \\
  > \"\$evidence_dir/k6.stdout.log\" \\
  2> \"\$evidence_dir/k6.stderr.log\""

python3 - \
  "$EVIDENCE_DIR/request.json" \
  "$LOAD_INSTANCE_ID" \
  "$REMOTE_COMMAND" \
  "$TEST_ID" \
  "$ATTEMPT_ID" \
  "$ARTIFACT_BUCKET" \
  "$RUN_ID" <<'PY'
import json
import sys

target, instance_id, command, test_id, attempt_id, artifact_bucket, run_id = sys.argv[1:]
payload = {
    "DocumentName": "AWS-RunShellScript",
    "InstanceIds": [instance_id],
    "Comment": f"GAJA k6 stage {test_id}",
    "Parameters": {"commands": [command]},
    "TimeoutSeconds": 900,
    "OutputS3BucketName": artifact_bucket,
    "OutputS3KeyPrefix": f"runs/{run_id}/evidence/{test_id}/{attempt_id}/ssm-output",
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

command_succeeded='YES'
if ! wait_for_ssm_command_with_evidence \
  "$AWS_REGION" "$COMMAND_ID" "$LOAD_INSTANCE_ID" \
  "$EVIDENCE_DIR/command-result.json"; then
  command_succeeded='NO'
fi

sync_succeeded='YES'
if ! aws s3 sync \
  "s3://$ARTIFACT_BUCKET/runs/$RUN_ID/evidence/$TEST_ID/$ATTEMPT_ID/" \
  "$EVIDENCE_DIR/" \
  --region "$AWS_REGION" \
  --only-show-errors; then
  sync_succeeded='NO'
fi
if ! aws s3 sync \
  "s3://$ARTIFACT_BUCKET/runs/$RUN_ID/evidence/$TEST_ID/$ATTEMPT_ID/ssm-output/" \
  "$EVIDENCE_DIR/ssm-output/" \
  --region "$AWS_REGION" \
  --only-show-errors; then
  sync_succeeded='NO'
fi

bash "$SCRIPT_DIR/render-k6-evidence-manifest.sh" \
  "$EVIDENCE_DIR" "$TEST_ID" "$ATTEMPT_ID"

bash "$SCRIPT_DIR/scan-k6-evidence-redaction.sh" \
  "$EVIDENCE_DIR" \
  "$EVIDENCE_DIR/redaction-scan.json"

if [[ "$command_succeeded" != 'YES' ]]; then
  if [[ -f "$EVIDENCE_DIR/command-result.json" ]]; then
    python3 - "$EVIDENCE_DIR/command-result.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    result = json.load(source)
print(
    f"k6 SSM command failed: status={result.get('Status')} "
    f"details={result.get('StatusDetails')} response_code={result.get('ResponseCode')}",
    file=sys.stderr,
)
PY
  else
    printf 'k6 SSM command failed and invocation evidence fetch failed\n' >&2
    [[ -f "$EVIDENCE_DIR/command-result.json.fetch-error.txt" ]] && \
      cat "$EVIDENCE_DIR/command-result.json.fetch-error.txt" >&2
  fi
  exit 1
fi

[[ "$sync_succeeded" == 'YES' ]] || {
  printf 'k6 evidence download failed for %s\n' "$TEST_ID" >&2
  exit 1
}

python3 - "$EVIDENCE_DIR/command-result.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    result = json.load(source)
if result.get("Status") != "Success":
    raise SystemExit(f"k6 stage failed: {result.get('StatusDetails')}")
PY

rm -f "$EVIDENCE_DIR/request.json"
printf 'k6_stage=PASS test_id=%s attempt_id=%s evidence=%s\n' \
  "$TEST_ID" "$ATTEMPT_ID" "$EVIDENCE_DIR"
