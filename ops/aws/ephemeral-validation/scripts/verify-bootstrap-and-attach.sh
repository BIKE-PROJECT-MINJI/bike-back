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
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/$RUN_ID/runtime-gate"
readonly RUNTIME_GATE_TIMEOUT_SECONDS=900
readonly RUNTIME_GATE_POLL_SECONDS=10
readonly RUNTIME_GATE_DEADLINE_EPOCH="$(( $(date +%s) + RUNTIME_GATE_TIMEOUT_SECONDS ))"
"$SCRIPT_DIR/assert-remaining-ttl.sh" 30
rm -rf "$EVIDENCE_DIR"
install -d -m 0700 "$EVIDENCE_DIR"

terraform -chdir="$STACK_DIR" output -json instance_ids >"$EVIDENCE_DIR/instance-ids.json"

wait_ssm_online() {
  local instance_id="$1"
  while (($(date +%s) < RUNTIME_GATE_DEADLINE_EPOCH)); do
    local ping_status
    ping_status="$(aws ssm describe-instance-information \
      --region "$AWS_REGION" \
      --filters "Key=InstanceIds,Values=$instance_id" \
      --query 'InstanceInformationList[0].PingStatus' \
      --output text 2>/dev/null || true)"
    if [[ "$ping_status" == "Online" ]]; then
      return 0
    fi
    sleep "$RUNTIME_GATE_POLL_SECONDS"
  done
  printf 'SSM did not become Online before global runtime deadline: %s\n' \
    "$instance_id" >&2
  return 1
}

wait_for_command_terminal() {
  local command_id="$1"
  local instance_id="$2"
  local timeout_seconds="$3"
  local deadline=$((SECONDS + timeout_seconds))

  while ((SECONDS < deadline)); do
    local status
    status="$(aws ssm get-command-invocation \
      --region "$AWS_REGION" \
      --command-id "$command_id" \
      --instance-id "$instance_id" \
      --query Status \
      --output text 2>/dev/null || true)"
    case "$status" in
      Success | Failed | Cancelled | TimedOut)
        printf '%s\n' "$status"
        return 0
        ;;
      Pending | InProgress | Delayed | '')
        sleep 2
        ;;
      *)
        printf 'unexpected SSM command status: %s\n' "$status" >&2
        return 1
        ;;
    esac
  done
  printf 'SSM command polling timed out: %s\n' "$command_id" >&2
  return 1
}

send_ssm_command() {
  local role="$1"
  local instance_id="$2"
  local comment_suffix="$3"
  local shell_command="$4"
  local timeout_seconds="$5"
  local request_file="$6"

  python3 - "$request_file" "$instance_id" "$shell_command" "$RUN_ID" \
    "$role" "$comment_suffix" "$timeout_seconds" <<'PY'
import json
import sys

target, instance_id, command, run_id, role, comment_suffix, timeout_seconds = sys.argv[1:]
payload = {
    "DocumentName": "AWS-RunShellScript",
    "InstanceIds": [instance_id],
    "Comment": f"GAJA {run_id} {role} {comment_suffix}",
    "Parameters": {"commands": [command]},
    "TimeoutSeconds": int(timeout_seconds),
}
with open(target, "w", encoding="utf-8") as output:
    json.dump(payload, output, ensure_ascii=True)
PY
  chmod 0600 "$request_file"
  aws ssm send-command \
    --region "$AWS_REGION" \
    --cli-input-json "file://$request_file" \
    --query 'Command.CommandId' \
    --output text
}

collect_runtime_diagnostics() {
  local request_file="$EVIDENCE_DIR/diagnostics-request.json"
  local result_file="$EVIDENCE_DIR/diagnostics-invocations.json"
  local diagnostic_command
  diagnostic_command="$(cat <<'EOF'
set +e
printf '%s\n' '=== timestamp ==='
date -u --iso-8601=seconds
printf '%s\n' '=== cloud-init ==='
cloud-init status --long 2>&1
printf '%s\n' '=== cloud-final service ==='
systemctl show cloud-final.service -p ActiveState -p SubState -p Result 2>&1
printf '%s\n' '=== cloud-final journal ==='
journalctl -u cloud-final.service --no-pager -n 80 2>&1
printf '%s\n' '=== ready markers ==='
find /opt/gaja-run -maxdepth 2 -name '*.ready' -printf '%p\n' 2>&1
printf '%s\n' '=== disk and memory ==='
df -h / /opt/gaja-run 2>&1
free -m 2>&1
printf '%s\n' '=== docker containers ==='
docker ps -a --no-trunc 2>&1
printf '%s\n' '=== bounded redacted container logs ==='
redact_logs() {
  python3 -c '
import pathlib
import re
import sys

payload = sys.stdin.read()
for secret_file in pathlib.Path("/run/gaja/secrets").glob("*"):
    try:
        secret = secret_file.read_text(encoding="utf-8").strip()
    except (OSError, UnicodeError):
        continue
    if secret:
        payload = payload.replace(secret, "[REDACTED_SECRET]")
patterns = (
    (r"(?i)(Bearer[ =:]+)[A-Za-z0-9._~+/=-]+", r"\1[REDACTED_TOKEN]"),
    (r"(?i)((?:password|secret|token|authorization)[ =:]+)[^\\s,;]+", r"\1[REDACTED]"),
    (r"(://)[^/@\\s]+:[^/@\\s]+@", r"\1[REDACTED_USERINFO]@"),
)
for pattern, replacement in patterns:
    payload = re.sub(pattern, replacement, payload)
sys.stdout.write(payload)
'
}
for container in gaja-back gaja-ai-route gaja-postgis gaja-redis gaja-graphhopper gaja-prometheus gaja-grafana; do
  if docker ps -a --format '{{.Names}}' | grep -qx "$container"; then
    printf '%s\n' "--- ${container} ---"
    docker logs --since 20m --tail 120 "$container" 2>&1 | redact_logs
  fi
done
printf '%s\n' '=== local HTTP probes ==='
for probe in 'http://127.0.0.1:8080/ready' 'http://127.0.0.1:8091/health' 'http://127.0.0.1:8989/health'; do
  curl -sS --max-time 5 -o /dev/null -w "${probe} status=%{http_code} error=%{errormsg}\n" "$probe" 2>&1
done
exit 0
EOF
)"

  local instance_ids
  instance_ids="$(python3 - "$EVIDENCE_DIR/instance-ids.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)
print(" ".join([*payload["app"].values(), *payload["singleton"].values()]))
PY
)"
  python3 - "$request_file" "$diagnostic_command" "$RUN_ID" $instance_ids <<'PY'
import json
import sys

target, command, run_id, *instance_ids = sys.argv[1:]
payload = {
    "DocumentName": "AWS-RunShellScript",
    "InstanceIds": instance_ids,
    "Comment": f"GAJA {run_id} parallel runtime diagnostics",
    "Parameters": {"commands": [command]},
    "TimeoutSeconds": 120,
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
  local diagnostics_deadline=$((SECONDS + 180))
  while ((SECONDS < diagnostics_deadline)); do
    aws ssm list-command-invocations \
      --region "$AWS_REGION" \
      --command-id "$command_id" \
      --details \
      --output json >"$result_file"
    if python3 - "$result_file" "$(wc -w <<<"$instance_ids")" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    invocations = json.load(source).get("CommandInvocations", [])
terminal = {"Success", "Failed", "Cancelled", "TimedOut"}
raise SystemExit(0 if len(invocations) == int(sys.argv[2]) and all(
    item.get("Status") in terminal for item in invocations
) else 1)
PY
    then
      break
    fi
    sleep 3
  done
  aws ssm list-command-invocations \
    --region "$AWS_REGION" \
    --command-id "$command_id" \
    --details \
    --output json >"$result_file"
  python3 - "$result_file" "$EVIDENCE_DIR/instance-ids.json" \
    "$EVIDENCE_DIR/diagnostics-manifest.json" <<'PY'
import json
import sys

result_path, instance_path, target_path = sys.argv[1:]
with open(result_path, encoding="utf-8") as source:
    invocations = json.load(source).get("CommandInvocations", [])
with open(instance_path, encoding="utf-8") as source:
    instance_payload = json.load(source)
roles = {value: key for key, value in {
    **instance_payload["app"], **instance_payload["singleton"]
}.items()}
by_instance = {item.get("InstanceId"): item for item in invocations}
manifest = []
for instance_id, role in roles.items():
    invocation = by_instance.get(instance_id)
    plugins = (invocation or {}).get("CommandPlugins", [])
    has_output = any(plugin.get("Output") for plugin in plugins)
    status = (invocation or {}).get("Status", "MISSING")
    manifest.append({
        "role": role,
        "instance_id": instance_id,
        "capture": "CAPTURED" if has_output else "UNAVAILABLE",
        "status": status,
        "reason": None if has_output else f"no diagnostic output; command status={status}",
    })
with open(target_path, "w", encoding="utf-8") as output:
    json.dump(manifest, output, ensure_ascii=True, indent=2)
    output.write("\n")
PY
  python3 - "$result_file" "$EVIDENCE_DIR/diagnostics-redaction-scan.json" <<'PY'
import json
import re
import sys

source_path, target_path = sys.argv[1:]
payload = open(source_path, encoding="utf-8").read()
patterns = {
    "aws_access_key": r"(?:AKIA|ASIA)[A-Z0-9]{16}",
    "jwt": r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+",
    "unredacted_bearer": r"(?i)Bearer[ =:]+(?!\[REDACTED)[A-Za-z0-9._~+/=-]{16,}",
    "url_userinfo": r"://[^/@\s]+:[^/@\s]+@",
}
matches = {name: bool(re.search(pattern, payload)) for name, pattern in patterns.items()}
result = {"pass": not any(matches.values()), "matches": matches}
with open(target_path, "w", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=True, indent=2)
    output.write("\n")
if not result["pass"]:
    raise SystemExit("runtime diagnostics redaction scan failed")
PY
  rm -f "$request_file"
}

wait_for_runtime_gate() {
  local role="$1"
  local instance_id="$2"
  local shell_command="$3"
  local request_file="$EVIDENCE_DIR/${role}-request.json"
  local remaining_seconds=$((RUNTIME_GATE_DEADLINE_EPOCH - $(date +%s)))
  if ((remaining_seconds <= 0)); then
    printf 'runtime gate timed out before role=%s\n' "$role" >&2
    return 1
  fi

  local wrapped_command
  wrapped_command="$(cat <<EOF
set -uo pipefail
deadline=\$((SECONDS + $remaining_seconds))
attempt=0
while ((SECONDS < deadline)); do
  attempt=\$((attempt + 1))
  if systemctl is-failed --quiet cloud-final.service; then
    printf 'runtime_gate_bootstrap_failed role=%s attempt=%s elapsed_seconds=%s\\n' '$role' "\$attempt" "\$SECONDS" >&2
    exit 2
  fi
  if { $shell_command; }; then
    printf 'runtime_gate_ready role=%s attempt=%s elapsed_seconds=%s\\n' '$role' "\$attempt" "\$SECONDS"
    exit 0
  fi
  printf 'runtime_gate_wait role=%s attempt=%s elapsed_seconds=%s\\n' '$role' "\$attempt" "\$SECONDS"
  sleep $RUNTIME_GATE_POLL_SECONDS
done
printf 'runtime gate timed out role=%s timeout_seconds=%s\\n' '$role' '$RUNTIME_GATE_TIMEOUT_SECONDS' >&2
exit 1
EOF
)"

  local command_id
  command_id="$(send_ssm_command "$role" "$instance_id" 'dependency gate' \
    "$wrapped_command" "$((remaining_seconds + 60))" "$request_file")"
  local status
  if ! status="$(wait_for_command_terminal "$command_id" "$instance_id" \
    "$((remaining_seconds + 90))")"; then
    status='PollingTimedOut'
  fi
  aws ssm get-command-invocation \
    --region "$AWS_REGION" \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --query '{status:Status,stdout:StandardOutputContent,stderr:StandardErrorContent}' \
    --output json >"$EVIDENCE_DIR/${role}-result.json"
  rm -f "$request_file"
  [[ "$status" == 'Success' ]]
}

ssm_online_failed=0
while IFS=$'\t' read -r role instance_id; do
  if ! wait_ssm_online "$instance_id"; then
    ssm_online_failed=1
    break
  fi
done < <(python3 - "$EVIDENCE_DIR/instance-ids.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    instance_ids = json.load(source)
for role, instance_id in {**instance_ids["app"], **instance_ids["singleton"]}.items():
    print(f"{role}\t{instance_id}")
PY
)
if ((ssm_online_failed)); then
  collect_runtime_diagnostics || true
  printf 'SSM online gate failed; diagnostics attempted under %s\n' "$EVIDENCE_DIR" >&2
  exit 1
fi

runtime_gate_failed=0
while IFS=$'\t' read -r role instance_id; do
  gate_command=''
  case "$role" in
    app-*)
      gate_command="test -f /opt/gaja-run/${role}.ready && curl -fsS --max-time 5 http://127.0.0.1:8091/health >/dev/null 2>&1 && curl -fsS --max-time 5 http://127.0.0.1:8080/ready >/dev/null 2>&1"
      ;;
    db)
      gate_command='test -f /opt/gaja-run/db.ready && docker exec gaja-postgis pg_isready -U bike -d bike >/dev/null'
      ;;
    redis)
      gate_command="test -f /opt/gaja-run/redis.ready && docker exec -i gaja-redis sh -c 'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; redis-cli --no-auth-warning ping' </run/gaja/secrets/redis.password | grep -qx PONG"
      ;;
    graphhopper)
      gate_command="test -f /opt/gaja-run/graphhopper.ready && curl -fsS --max-time 10 'http://127.0.0.1:8989/route?profile=bike&point=37.481247,126.952739&point=37.551200,126.988200&points_encoded=false' >/dev/null 2>&1"
      ;;
    load)
      gate_command="test -f /opt/gaja-run/${role}.ready"
      ;;
    observability)
      gate_command="test -f /opt/gaja-run/observability.ready && source /opt/gaja-run/role/role.env && /opt/gaja-run/verify-observability.sh && targets_json=\$(curl -fsS --max-time 5 http://127.0.0.1:9090/api/v1/targets) && healthy_targets=\$(grep -o '\"health\":\"up\"' <<<\"\$targets_json\" | wc -l || true) && ((healthy_targets >= EXPECTED_APP_TARGETS))"
      ;;
    *)
      printf 'unexpected role in Terraform output: %s\n' "$role" >&2
      exit 1
      ;;
  esac
  if ! wait_for_runtime_gate "$role" "$instance_id" "$gate_command"; then
    runtime_gate_failed=1
    break
  fi
done < <(python3 - "$EVIDENCE_DIR/instance-ids.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    instance_ids = json.load(source)
for role, instance_id in {**instance_ids["app"], **instance_ids["singleton"]}.items():
    print(f"{role}\t{instance_id}")
PY
)

if ((runtime_gate_failed)); then
  collect_runtime_diagnostics || true
  printf 'runtime gate failed; diagnostics attempted under %s\n' "$EVIDENCE_DIR" >&2
  exit 1
fi

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
