#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s <evidence-dir> <scan-json>\n' "$0" >&2
  exit 2
fi

readonly EVIDENCE_DIR="$1"
readonly SCAN_FILE="$2"

python3 - "$EVIDENCE_DIR" "$SCAN_FILE" <<'PY'
import json
import pathlib
import re
import sys

source_dir, target_path = sys.argv[1:]
source_path = pathlib.Path(source_dir)
scanned = sorted(
    path for path in source_path.glob("diagnostics-*.json")
    if path.name not in {
        "diagnostics-request.json",
        "diagnostics-manifest.json",
        "diagnostics-redaction-scan.json",
    }
)


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from strings(item)
    elif isinstance(value, dict):
        for item in value.values():
            yield from strings(item)


payload_parts = []
invalid_json_files = []
for path in scanned:
    raw = path.read_text(encoding="utf-8")
    try:
        payload_parts.extend(strings(json.loads(raw)))
    except json.JSONDecodeError:
        payload_parts.append(raw)
        invalid_json_files.append(path.name)
payload = "\n".join(payload_parts)
patterns = {
    "aws_access_key": r"(?:AKIA|ASIA)[A-Z0-9]{16}",
    "jwt": r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+",
    "unredacted_bearer": r"(?i)Bearer[ =:]+(?!\[REDACTED)[A-Za-z0-9._~+/=-]{16,}",
    "url_userinfo": r"://[^/@\s]+:[^/@\s]+@",
    "generic_secret_assignment": r"(?i)(?:password|passwd|secret|token|authorization|api[_-]?key|client[_-]?secret)\s*[=:]\s*(?!\[REDACTED)[^\s,;]{4,}",
    "aws_signed_query": r"(?i)X-Amz-(?:Credential|Signature|Security-Token)=(?!\[REDACTED)[^&\s]+",
    "google_api_key": r"AIza[0-9A-Za-z_-]{30,}",
    "private_key": r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    "parse_exception_value": r"(?i)For input string:\s*\"(?!\[REDACTED_PARSE_VALUE\])[^\"]+\"",
}
matches = {name: bool(re.search(pattern, payload)) for name, pattern in patterns.items()}
matches["invalid_json"] = bool(invalid_json_files)
result = {
    "pass": not any(matches.values()),
    "scanned_files": [path.name for path in scanned],
    "invalid_json_files": invalid_json_files,
    "matches": matches,
}
with open(target_path, "w", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=True, indent=2)
    output.write("\n")
if not result["pass"]:
    raise SystemExit("runtime diagnostics redaction scan failed")
PY
