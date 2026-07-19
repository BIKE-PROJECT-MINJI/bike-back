#!/usr/bin/env bash
set -euo pipefail

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$TEST_DIR/.." && pwd)"
# shellcheck source=../scripts/ssm-command-evidence.sh
source "$STACK_DIR/scripts/ssm-command-evidence.sh"

readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
FAKE_FETCH_ERROR='NO'
FAKE_TRANSIENT_ERROR='NO'
readonly QUERY_COUNT_FILE="$TEMP_DIR/query-count.txt"
printf '0\n' >"$QUERY_COUNT_FILE"

aws() {
  if [[ "$FAKE_FETCH_ERROR" == 'YES' ]]; then
    printf 'AccessDenied: GetCommandInvocation\n' >&2
    return 254
  fi
  if [[ " $* " == *" --query Status "* ]]; then
    if [[ "$FAKE_TRANSIENT_ERROR" == 'YES' && "$(<"$QUERY_COUNT_FILE")" == '0' ]]; then
      printf '1\n' >"$QUERY_COUNT_FILE"
      printf 'InvocationDoesNotExist: command is not visible yet\n' >&2
      return 254
    fi
    printf 'Failed\n'
    return 0
  fi
  printf '%s\n' '{"Status":"Failed","StatusDetails":"Failed","ResponseCode":1,"StandardOutputContent":"k6 started","StandardErrorContent":"threshold crossed"}'
}

# Given: SSM accepted a command that reaches a terminal Failed state.
persist_ssm_command_id "$TEMP_DIR" 'command-123'

# When: the runner waits for the command and captures its terminal invocation.
if wait_for_ssm_command_with_evidence \
  'ap-northeast-2' 'command-123' 'instance-123' \
  "$TEMP_DIR/command-result.json" 1 0; then
  printf 'expected failed SSM command to return non-zero\n' >&2
  exit 1
fi

# Then: both the correlation ID and the terminal stdout/stderr evidence remain.
[[ "$(cat "$TEMP_DIR/command-id.txt")" == 'command-123' ]]
python3 - "$TEMP_DIR/command-result.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    result = json.load(source)

assert result["Status"] == "Failed"
assert result["ResponseCode"] == 1
assert result["StandardOutputContent"] == "k6 started"
assert result["StandardErrorContent"] == "threshold crossed"
PY

readonly FETCH_ERROR_DIR="$TEMP_DIR/fetch-error"
FAKE_FETCH_ERROR='YES'

# Given: SSM accepted a command but the terminal invocation lookup is denied.
persist_ssm_command_id "$FETCH_ERROR_DIR" 'command-456'

# When: the runner attempts to capture the terminal invocation.
if wait_for_ssm_command_with_evidence \
  'ap-northeast-2' 'command-456' 'instance-456' \
  "$FETCH_ERROR_DIR/command-result.json" 1 0; then
  printf 'expected invocation fetch failure to return non-zero\n' >&2
  exit 1
fi

# Then: the command ID and poll error survive immediately without result JSON.
[[ "$(cat "$FETCH_ERROR_DIR/command-id.txt")" == 'command-456' ]]
[[ ! -e "$FETCH_ERROR_DIR/command-result.json" ]]
grep -Fq 'AccessDenied: GetCommandInvocation' \
  "$FETCH_ERROR_DIR/command-poll-error.txt"

readonly TRANSIENT_DIR="$TEMP_DIR/transient"
FAKE_FETCH_ERROR='NO'
FAKE_TRANSIENT_ERROR='YES'
printf '0\n' >"$QUERY_COUNT_FILE"

# Given: the command is briefly invisible to GetCommandInvocation.
persist_ssm_command_id "$TRANSIENT_DIR" 'command-789'

# When: the runner retries the documented transient lookup error.
if wait_for_ssm_command_with_evidence \
  'ap-northeast-2' 'command-789' 'instance-789' \
  "$TRANSIENT_DIR/command-result.json" 2 0; then
  printf 'expected terminal Failed result after transient lookup\n' >&2
  exit 1
fi

# Then: transient lookup does not hide the terminal invocation evidence.
[[ -f "$TRANSIENT_DIR/command-result.json" ]]
[[ ! -e "$TRANSIENT_DIR/command-poll-error.txt" ]]

printf 'ssm_command_failure_evidence=PASS\n'
