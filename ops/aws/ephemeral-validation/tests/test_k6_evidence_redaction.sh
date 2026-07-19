#!/usr/bin/env bash
set -euo pipefail

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$TEST_DIR/.." && pwd)"
readonly SCANNER="$STACK_DIR/scripts/scan-k6-evidence-redaction.sh"
readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

# Given: a normal failed k6 evidence bundle without credentials.
printf '%s\n' 'threshold crossed' >"$TEMP_DIR/k6.stderr.log"

# When: the evidence is scanned.
"$SCANNER" "$TEMP_DIR" "$TEMP_DIR/redaction-scan.json"

# Then: the safe bundle is accepted.
python3 - "$TEMP_DIR/redaction-scan.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    assert json.load(source)["pass"] is True
PY

# Given: the same bundle contains a JWT-shaped value.
printf '%s\n' 'eyJhbGciOiJIUzI1NiJ9.c2VjcmV0.c2lnbmF0dXJl' >"$TEMP_DIR/k6.stdout.log"

# When/Then: scanning fails without printing the credential value.
if "$SCANNER" "$TEMP_DIR" "$TEMP_DIR/redaction-scan.json" >/dev/null 2>"$TEMP_DIR/scan-error.log"; then
  printf 'expected credential-shaped evidence to fail redaction\n' >&2
  exit 1
fi
grep -Fq 'k6 evidence redaction scan failed' "$TEMP_DIR/scan-error.log"

printf 'k6_evidence_redaction=PASS\n'
