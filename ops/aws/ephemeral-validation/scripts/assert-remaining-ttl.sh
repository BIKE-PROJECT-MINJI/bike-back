#!/usr/bin/env bash
set -euo pipefail

readonly MIN_REMAINING_MINUTES="${1:?pass minimum remaining minutes}"
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"

[[ "$MIN_REMAINING_MINUTES" =~ ^[0-9]+$ ]] || {
  printf 'minimum remaining minutes must be a non-negative integer\n' >&2
  exit 1
}
[[ -f "$TFVARS" ]] || {
  printf 'run preflight.sh first\n' >&2
  exit 1
}

readonly CLEANUP_START_AT="$(python3 - "$TFVARS" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)["cleanup_start_at"])
PY
)"
readonly CLEANUP_EPOCH="$(date -u -d "${CLEANUP_START_AT}Z" +%s)"
readonly NOW_EPOCH="$(date -u +%s)"
readonly REMAINING_SECONDS="$((CLEANUP_EPOCH - NOW_EPOCH))"
readonly REQUIRED_SECONDS="$((MIN_REMAINING_MINUTES * 60))"

((REMAINING_SECONDS >= REQUIRED_SECONDS)) || {
  printf 'remaining TTL gate failed: remaining=%ss required=%ss cleanup=%sZ\n' \
    "$REMAINING_SECONDS" "$REQUIRED_SECONDS" "$CLEANUP_START_AT" >&2
  exit 1
}

printf 'remaining_ttl_gate=PASS remaining_seconds=%s required_seconds=%s cleanup=%sZ\n' \
  "$REMAINING_SECONDS" "$REQUIRED_SECONDS" "$CLEANUP_START_AT"
