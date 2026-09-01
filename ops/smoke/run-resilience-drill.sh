#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# Gate B is print-only. --execute is deliberately rejected; a lifecycle-safe
# executor requires a separate Gate C task and approval design.
exec python3 "$SCRIPT_DIR/resilience_contract.py" "$@"
