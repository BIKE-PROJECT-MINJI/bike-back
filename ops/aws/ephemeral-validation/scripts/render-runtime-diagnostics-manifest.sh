#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  printf 'usage: %s <evidence-dir> <instance-ids-json> <manifest-json>\n' "$0" >&2
  exit 2
fi

readonly EVIDENCE_DIR="$1"
readonly INSTANCE_IDS_FILE="$2"
readonly MANIFEST_FILE="$3"

python3 - "$EVIDENCE_DIR" "$INSTANCE_IDS_FILE" "$MANIFEST_FILE" <<'PY'
import json
import pathlib
import sys

evidence_dir, instance_path, target_path = sys.argv[1:]
evidence_path = pathlib.Path(evidence_dir)
with open(instance_path, encoding="utf-8") as source:
    instance_payload = json.load(source)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


manifest = []
for role, instance_id in {**instance_payload["app"], **instance_payload["singleton"]}.items():
    detail = read_json(evidence_path / f"diagnostics-{role}.json")
    runtime = read_json(evidence_path / f"{role}-result.json")
    stdout = detail.get("StandardOutputContent", "")
    stderr = detail.get("StandardErrorContent", "")
    diagnostic_status = detail.get("Status", "MISSING")
    runtime_status = runtime.get("status", "NOT_RUN")
    stdout_truncated = "---Output truncated---" in stdout or len(stdout) >= 23_900
    stderr_truncated = "---Output truncated---" in stderr or len(stderr) >= 7_900
    potentially_truncated = stdout_truncated or stderr_truncated
    output_bytes = len((stdout + stderr).encode("utf-8"))
    has_output = bool(stdout or stderr)

    if diagnostic_status in {"UNAVAILABLE", "MISSING"}:
        capture = "UNAVAILABLE"
        reason = f"diagnostic detail unavailable; command status={diagnostic_status}"
    elif diagnostic_status != "Success":
        capture = "PARTIAL"
        reason = f"diagnostic command incomplete; command status={diagnostic_status}"
    elif potentially_truncated:
        capture = "PARTIAL"
        reason = "diagnostic output may be truncated by SSM limits"
    elif has_output:
        capture = "CAPTURED"
        reason = None
    else:
        capture = "UNAVAILABLE"
        reason = "diagnostic command succeeded without output"

    manifest.append({
        "role": role,
        "instance_id": instance_id,
        "capture": capture,
        "runtime_gate_status": runtime_status,
        "diagnostic_command_status": diagnostic_status,
        "output_bytes": output_bytes,
        "stdout_characters": len(stdout),
        "stderr_characters": len(stderr),
        "potentially_truncated": potentially_truncated,
        "reason": reason,
    })

with open(target_path, "w", encoding="utf-8") as output:
    json.dump(manifest, output, ensure_ascii=True, indent=2)
    output.write("\n")
PY
