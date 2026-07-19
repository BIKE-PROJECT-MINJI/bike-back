#!/usr/bin/env bash
set -euo pipefail

readonly ALLOW_AWS_APPLY="${ALLOW_AWS_APPLY:-NO}"
[[ "$ALLOW_AWS_APPLY" == "YES" ]] || {
  printf 'set ALLOW_AWS_APPLY=YES after reviewing the runtime gate\n' >&2
  exit 1
}

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/runtime-gate"

for command in aws python3 terraform; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done
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
install -d -m 0700 "$EVIDENCE_DIR"

terraform -chdir="$STACK_DIR" output -json instance_ids >"$EVIDENCE_DIR/instance-ids.json"

wait_ssm_online() {
  local instance_id="$1"
  for attempt in $(seq 1 60); do
    local ping_status
    ping_status="$(aws ssm describe-instance-information \
      --region "$AWS_REGION" \
      --filters "Key=InstanceIds,Values=$instance_id" \
      --query 'InstanceInformationList[0].PingStatus' \
      --output text 2>/dev/null || true)"
    if [[ "$ping_status" == "Online" ]]; then
      return 0
    fi
    sleep 10
  done
  printf 'SSM did not become Online: %s\n' "$instance_id" >&2
  return 1
}

run_ssm_gate() {
  local role="$1"
  local instance_id="$2"
  local shell_command="$3"
  local request_file="$EVIDENCE_DIR/${role}-request.json"

  python3 - "$request_file" "$instance_id" "$shell_command" "$RUN_ID" "$role" <<'PY'
import json
import sys

target, instance_id, command, run_id, role = sys.argv[1:]
payload = {
    "DocumentName": "AWS-RunShellScript",
    "InstanceIds": [instance_id],
    "Comment": f"GAJA {run_id} {role} dependency gate",
    "Parameters": {"commands": [command]},
    "TimeoutSeconds": 180,
}
with open(target, "w", encoding="utf-8") as output:
    json.dump(payload, output, ensure_ascii=True)
PY
  chmod 0600 "$request_file"

  local command_id
  command_id="$(aws ssm send-command \
    --region "$AWS_REGION" \
    --cli-input-json "file://$request_file" \
    --query 'Command.CommandId' \
    --output text)"
  aws ssm wait command-executed \
    --region "$AWS_REGION" \
    --command-id "$command_id" \
    --instance-id "$instance_id"
  aws ssm get-command-invocation \
    --region "$AWS_REGION" \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --query '{status:Status,stdout:StandardOutputContent,stderr:StandardErrorContent}' \
    --output json >"$EVIDENCE_DIR/${role}-result.json"
  python3 - "$EVIDENCE_DIR/${role}-result.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    result = json.load(source)
if result.get("status") != "Success":
    raise SystemExit(f"SSM runtime gate failed: {result}")
PY
  rm -f "$request_file"
}

while IFS=$'\t' read -r role instance_id; do
  wait_ssm_online "$instance_id"
  case "$role" in
    app-*)
      run_ssm_gate "$role" "$instance_id" \
        "test -f /opt/gaja-run/${role}.ready && curl -fsS --max-time 5 http://127.0.0.1:8091/health >/dev/null && curl -fsS --max-time 5 http://127.0.0.1:8080/ready >/dev/null"
      ;;
    db)
      run_ssm_gate "$role" "$instance_id" \
        'test -f /opt/gaja-run/db.ready && docker exec gaja-postgis pg_isready -U bike -d bike >/dev/null'
      ;;
    redis)
      run_ssm_gate "$role" "$instance_id" \
        "test -f /opt/gaja-run/redis.ready && docker exec -i gaja-redis sh -c 'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; redis-cli --no-auth-warning ping' </run/gaja/secrets/redis.password | grep -qx PONG"
      ;;
    graphhopper)
      run_ssm_gate "$role" "$instance_id" \
        "test -f /opt/gaja-run/graphhopper.ready && curl -fsS --max-time 10 'http://127.0.0.1:8989/route?profile=bike&point=37.481247,126.952739&point=37.551200,126.988200&points_encoded=false' >/dev/null"
      ;;
    load)
      run_ssm_gate "$role" "$instance_id" "test -f /opt/gaja-run/${role}.ready"
      ;;
    observability)
      run_ssm_gate "$role" "$instance_id" \
        "test -f /opt/gaja-run/observability.ready && source /opt/gaja-run/role/role.env && /opt/gaja-run/verify-observability.sh && targets_json=\$(curl -fsS --max-time 5 http://127.0.0.1:9090/api/v1/targets) && healthy_targets=\$(grep -o '\"health\":\"up\"' <<<\"\$targets_json\" | wc -l || true) && ((healthy_targets >= EXPECTED_APP_TARGETS))"
      ;;
    *)
      printf 'unexpected role in Terraform output: %s\n' "$role" >&2
      exit 1
      ;;
  esac
done < <(python3 - "$EVIDENCE_DIR/instance-ids.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    instance_ids = json.load(source)
for role, instance_id in {**instance_ids["app"], **instance_ids["singleton"]}.items():
    print(f"{role}\t{instance_id}")
PY
)

terraform -chdir="$STACK_DIR" apply \
  -input=false \
  -auto-approve \
  -var='attach_app_targets=true'

readonly TARGET_GROUP_ARN="$(terraform -chdir="$STACK_DIR" output -raw target_group_arn)"
while IFS= read -r instance_id; do
  aws elbv2 wait target-in-service \
    --region "$AWS_REGION" \
    --target-group-arn "$TARGET_GROUP_ARN" \
    --targets "Id=$instance_id"
done < <(python3 - "$EVIDENCE_DIR/instance-ids.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    instance_ids = json.load(source)
for instance_id in instance_ids["app"].values():
    print(instance_id)
PY
)

aws elbv2 describe-target-health \
  --region "$AWS_REGION" \
  --target-group-arn "$TARGET_GROUP_ARN" \
  --output json >"$EVIDENCE_DIR/target-health.json"

printf 'runtime_gate=PASS targets_attached=true evidence=%s\n' "$EVIDENCE_DIR"
