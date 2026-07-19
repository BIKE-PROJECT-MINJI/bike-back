#!/usr/bin/env bash

persist_ssm_command_id() {
  local -r evidence_dir="${1:?pass evidence directory}"
  local -r command_id="${2:?pass command id}"
  local -r target="$evidence_dir/command-id.txt"
  local -r temporary="$target.tmp"

  install -d -m 0700 "$evidence_dir"
  (umask 077 && printf '%s\n' "$command_id" >"$temporary")
  mv "$temporary" "$target"
}

wait_for_ssm_command_with_evidence() {
  local -r region="${1:?pass AWS region}"
  local -r command_id="${2:?pass command id}"
  local -r instance_id="${3:?pass instance id}"
  local -r result_file="${4:?pass result file}"
  local -r max_attempts="${5:-180}"
  local -r poll_seconds="${6:-5}"
  local -r poll_error="$result_file.poll-error.tmp"
  local -r preserved_poll_error="$(dirname "$result_file")/command-poll-error.txt"
  local status='Pending'
  local attempt

  for attempt in $(seq 1 "$max_attempts"); do
    if status="$(aws ssm get-command-invocation \
      --region "$region" \
      --command-id "$command_id" \
      --instance-id "$instance_id" \
      --query Status \
      --output text 2>"$poll_error")"; then
      rm -f "$poll_error"
    elif grep -Fq 'InvocationDoesNotExist' "$poll_error"; then
      rm -f "$poll_error"
      status='Pending'
    else
      mv "$poll_error" "$preserved_poll_error"
      chmod 0600 "$preserved_poll_error"
      return 1
    fi
    case "$status" in
      Success | Failed | Cancelled | TimedOut)
        break
        ;;
      Pending | InProgress | Delayed)
        if ((poll_seconds > 0)); then
          sleep "$poll_seconds"
        fi
        ;;
      *)
        break
        ;;
    esac
  done

  local -r temporary="$result_file.tmp"
  local -r fetch_error="$result_file.fetch-error.txt"
  if ! aws ssm get-command-invocation \
    --region "$region" \
    --command-id "$command_id" \
    --instance-id "$instance_id" \
    --output json >"$temporary" 2>"$fetch_error"; then
    rm -f "$temporary"
    return 1
  fi
  mv "$temporary" "$result_file"
  chmod 0600 "$result_file"
  [[ -s "$fetch_error" ]] || rm -f "$fetch_error"

  status="$(python3 - "$result_file" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source).get("Status", "MISSING"))
PY
)"
  [[ "$status" == 'Success' ]]
}
