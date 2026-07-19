#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"

for command in python3 terraform; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

readonly RUN_ID="$(python3 - "$TFVARS" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    print(json.load(source)["run_id"])
PY
)"
readonly PLAN_DIR="$STACK_DIR/.artifacts/$RUN_ID/terraform-plan"
readonly PLAN_FILE="$PLAN_DIR/create-only.tfplan"
readonly PLAN_JSON="$PLAN_DIR/create-only.json"
install -d -m 0700 "$PLAN_DIR"

terraform -chdir="$STACK_DIR" init -input=false
terraform -chdir="$STACK_DIR" plan -input=false -out="$PLAN_FILE"
terraform -chdir="$STACK_DIR" show -json "$PLAN_FILE" >"$PLAN_JSON"

python3 - "$PLAN_JSON" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    plan = json.load(source)
forbidden = []
creates = 0
for change in plan.get("resource_changes", []):
    actions = change.get("change", {}).get("actions", [])
    address = change.get("address", "UNKNOWN")
    if "delete" in actions or "update" in actions:
        forbidden.append({"address": address, "actions": actions})
    if actions == ["create"]:
        creates += 1
if forbidden:
    raise SystemExit(f"create-only plan gate failed: {forbidden}")
if creates == 0:
    raise SystemExit("create-only plan gate failed: no creates")
print(f"create_only_plan=PASS creates={creates}")
PY

printf '%s\n' "$PLAN_FILE"
