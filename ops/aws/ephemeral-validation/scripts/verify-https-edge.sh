#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"

read_tfvar() {
  python3 - "$TFVARS" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)[sys.argv[2]])
PY
}

readonly RUN_ID="$(read_tfvar run_id)"
readonly DOMAIN_NAME="$(read_tfvar domain_name)"
readonly ALB_DNS_NAME="$(terraform -chdir="$STACK_DIR" output -raw alb_dns_name)"
readonly EVIDENCE_DIR="$STACK_DIR/.artifacts/$RUN_ID/https-edge"
rm -rf "$EVIDENCE_DIR"
install -d -m 0700 "$EVIDENCE_DIR"

readonly STATUS="$(curl -sS \
  --connect-to "$DOMAIN_NAME:443:$ALB_DNS_NAME:443" \
  --max-time 15 \
  --dump-header "$EVIDENCE_DIR/headers.txt" \
  --output "$EVIDENCE_DIR/body.json" \
  --write-out '%{http_code}' \
  "https://$DOMAIN_NAME/health")"
printf '%s\n' "$STATUS" >"$EVIDENCE_DIR/status.txt"

[[ "$STATUS" == '200' ]] || {
  printf 'HTTPS edge health failed with status %s\n' "$STATUS" >&2
  exit 1
}
grep -Eiq '^x-request-id: [^[:space:]]+' "$EVIDENCE_DIR/headers.txt"
grep -Eiq '^x-trace-id: [^[:space:]]+' "$EVIDENCE_DIR/headers.txt"
grep -Eiq '^strict-transport-security:' "$EVIDENCE_DIR/headers.txt"
bash "$SCRIPT_DIR/scan-k6-evidence-redaction.sh" \
  "$EVIDENCE_DIR" \
  "$EVIDENCE_DIR/redaction-scan.json"
printf 'https_edge=PASS status=200 evidence=%s\n' "$EVIDENCE_DIR"
