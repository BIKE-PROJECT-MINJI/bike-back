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
target = pathlib.Path(target_path).resolve()
scanned = sorted(
    path for path in source_path.rglob("*")
    if path.is_file() and path.resolve() != target and path.name != "request.json"
)
payload = "\n".join(path.read_bytes().decode("utf-8", errors="replace") for path in scanned)
patterns = {
    "aws_access_key": r"(?:AKIA|ASIA)[A-Z0-9]{16}",
    "jwt": r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+",
    "unredacted_bearer": r"(?i)Bearer[ =:]+(?!\[REDACTED)[A-Za-z0-9._~+/=-]{16,}",
    "url_userinfo": r"://[^/@\s]+:[^/@\s]+@",
    "generic_secret_assignment": r"(?i)(?:password|passwd|secret|token|authorization|api[_-]?key|client[_-]?secret)\s*[=:]\s*(?!\[REDACTED)[^\s,;]{4,}",
    "aws_signed_query": r"(?i)X-Amz-(?:Credential|Signature|Security-Token)=(?!\[REDACTED)[^&\s]+",
    "google_api_key": r"AIza[0-9A-Za-z_-]{30,}",
    "private_key": r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
}
matches = {name: bool(re.search(pattern, payload)) for name, pattern in patterns.items()}
result = {
    "pass": not any(matches.values()),
    "scanned_files": [str(path.relative_to(source_path)) for path in scanned],
    "matches": matches,
}
with open(target, "w", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=True, indent=2)
    output.write("\n")
if not result["pass"]:
    raise SystemExit("k6 evidence redaction scan failed")
PY
