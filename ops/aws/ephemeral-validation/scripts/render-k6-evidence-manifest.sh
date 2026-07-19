#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  printf 'usage: %s <evidence-dir> <test-id> <attempt-id>\n' "$0" >&2
  exit 2
fi

python3 - "$1" "$2" "$3" <<'PY'
import json
import pathlib
import sys

evidence_dir = pathlib.Path(sys.argv[1])
result_path = evidence_dir / "command-result.json"
status = "POLL_ERROR"
status_details = None
response_code = None
if result_path.exists():
    result = json.loads(result_path.read_text(encoding="utf-8"))
    status = result.get("Status", "UNKNOWN")
    status_details = result.get("StatusDetails")
    response_code = result.get("ResponseCode")
summary_files = sorted({
    path.name
    for pattern in ("summary.json", "*-summary.json")
    for path in evidence_dir.glob(pattern)
    if path.is_file()
})
manifest = {
    "test_id": sys.argv[2],
    "attempt_id": sys.argv[3],
    "command_status": status,
    "status_details": status_details,
    "response_code": response_code,
    "summary": "PRODUCED" if summary_files else "NOT_PRODUCED",
    "summary_files": summary_files,
}
(evidence_dir / "evidence-manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=True, indent=2) + "\n",
    encoding="utf-8",
)
PY
