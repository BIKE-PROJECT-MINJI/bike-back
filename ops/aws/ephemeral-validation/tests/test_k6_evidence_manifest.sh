#!/usr/bin/env bash
set -euo pipefail

readonly TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly RENDERER="$TEST_DIR/../scripts/render-k6-evidence-manifest.sh"
readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

printf '%s\n' '{"Status":"Success","StatusDetails":"Success","ResponseCode":0}' \
  >"$TEMP_DIR/command-result.json"

printf '%s\n' '{}' >"$TEMP_DIR/run-smoke-attempt-1-summary.json"
"$RENDERER" "$TEMP_DIR" run-smoke run-smoke-attempt-1
python3 - "$TEMP_DIR/evidence-manifest.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
assert manifest["summary"] == "PRODUCED"
assert manifest["summary_files"] == ["run-smoke-attempt-1-summary.json"]
assert manifest["attempt_id"] == "run-smoke-attempt-1"
PY

rm -f "$TEMP_DIR/run-smoke-attempt-1-summary.json"
printf '%s\n' '{}' >"$TEMP_DIR/summary.json"
"$RENDERER" "$TEMP_DIR" run-ai run-ai-attempt-2
python3 - "$TEMP_DIR/evidence-manifest.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    manifest = json.load(source)
assert manifest["summary"] == "PRODUCED"
assert manifest["summary_files"] == ["summary.json"]
assert manifest["attempt_id"] == "run-ai-attempt-2"
PY

rm -f "$TEMP_DIR/summary.json"
"$RENDERER" "$TEMP_DIR" run-timeout run-timeout-attempt-3
grep -Fq '"summary": "NOT_PRODUCED"' "$TEMP_DIR/evidence-manifest.json"

printf 'k6_evidence_manifest=PASS\n'
