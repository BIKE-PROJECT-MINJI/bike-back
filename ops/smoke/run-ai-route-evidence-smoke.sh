#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BIKE_SMOKE_BASE_URL:-http://127.0.0.1:8080}"
GRAPHHOPPER_URL="${BIKE_SMOKE_GRAPHHOPPER_URL:-http://127.0.0.1:8989}"
RESULTS_DIR="${RESULTS_DIR:-$ROOT_DIR/ops/smoke/results/ai-route-evidence-smoke-$(date +%Y%m%d-%H%M%S)}"

RUN_GRAPHHOPPER_DIRECT="${RUN_GRAPHHOPPER_DIRECT:-true}"
RUN_OPENAPI_CONTRACT="${RUN_OPENAPI_CONTRACT:-true}"
RUN_AI_ROUTE_SMOKE="${RUN_AI_ROUTE_SMOKE:-true}"
VALIDATE_ONLY="${VALIDATE_ONLY:-false}"

mkdir -p "$RESULTS_DIR"

log() {
  printf '[%s] %s\n' "$(date -Iseconds)" "$*"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required command: $1" >&2
    exit 10
  fi
}

write_summary() {
  local status="$1"
  python3 - "$RESULTS_DIR" "$status" "$BASE_URL" "$GRAPHHOPPER_URL" <<'PY'
import json
import pathlib
import sys
from datetime import datetime, timezone

results_dir = pathlib.Path(sys.argv[1])
steps = []
for path in sorted(results_dir.glob("*.step.json")):
    with path.open("r", encoding="utf-8") as handle:
        steps.append(json.load(handle))
summary = {
    "status": sys.argv[2],
    "baseUrl": sys.argv[3],
    "graphhopperUrl": sys.argv[4],
    "createdAt": datetime.now(timezone.utc).isoformat(),
    "steps": steps,
}
with (results_dir / "summary.json").open("w", encoding="utf-8") as handle:
    json.dump(summary, handle, ensure_ascii=False, indent=2)
PY
}

record_step() {
  local label="$1"
  local status="$2"
  local body_file="$3"
  cat >"$RESULTS_DIR/$label.step.json" <<EOF
{
  "label": "$label",
  "status": "$status",
  "bodyFile": "$(basename "$body_file")"
}
EOF
}

http_json() {
  local label="$1"
  local method="$2"
  local url="$3"
  local payload="${4:-}"
  shift 4 || true

  local body_file="$RESULTS_DIR/$label.body.json"
  local status_file="$RESULTS_DIR/$label.status"
  local status
  local -a args
  args=(-sS -X "$method" "$url" -o "$body_file" -w "%{http_code}" -H "Accept: application/json")
  if [[ -n "$payload" ]]; then
    args+=(-H "Content-Type: application/json" --data "$payload")
  fi
  args+=("$@")
  status="$(curl "${args[@]}")"
  printf '%s' "$status" >"$status_file"
  record_step "$label" "$status" "$body_file"
}

require_status() {
  local label="$1"
  local expected="$2"
  local actual
  actual="$(cat "$RESULTS_DIR/$label.status")"
  if [[ "$actual" != "$expected" ]]; then
    echo "$label expected HTTP $expected but got $actual" >&2
    echo "body: $RESULTS_DIR/$label.body.json" >&2
    exit 20
  fi
}

assert_graphhopper_route() {
  local label="$1"
  python3 - "$RESULTS_DIR/$label.body.json" "$label" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
paths = payload.get("paths")
if not isinstance(paths, list) or not paths:
    raise SystemExit(f"{sys.argv[2]}: expected non-empty paths")
details = paths[0].get("details")
if not isinstance(details, dict):
    raise SystemExit(f"{sys.argv[2]}: expected route details")
required = {"average_slope", "bike_network", "max_slope", "road_class", "road_environment", "smoothness", "surface"}
missing = sorted(required.difference(details))
if missing:
    raise SystemExit(f"{sys.argv[2]}: missing route details {missing}")
PY
}

assert_openapi_fields() {
  python3 - "$RESULTS_DIR/openapi.body.json" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    raw = handle.read()
json.loads(raw)
required = [
    "preferenceSummary",
    "elevationStatus",
    "sceneryEvidenceStatus",
    "routingMetadata",
    "aiWorkerMetadata",
    "evidenceBadges",
]
missing = [field for field in required if field not in raw]
if missing:
    raise SystemExit(f"OpenAPI missing fields: {missing}")
PY
}

assert_ai_route_response() {
  local label="$1"
  python3 - "$RESULTS_DIR/$label.body.json" "$label" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
if payload.get("code") != 200:
    raise SystemExit(f"{sys.argv[2]}: expected code=200, got {payload.get('code')}")
data = payload.get("data")
if not isinstance(data, dict):
    raise SystemExit(f"{sys.argv[2]}: expected data object")
routing = data.get("routingMetadata")
if not isinstance(routing, dict):
    raise SystemExit(f"{sys.argv[2]}: expected routingMetadata object")
if routing.get("provider") != "GRAPHHOPPER":
    raise SystemExit(f"{sys.argv[2]}: expected GraphHopper routing provider, got {routing.get('provider')}")
if not data.get("preferenceSummary"):
    raise SystemExit(f"{sys.argv[2]}: preferenceSummary is empty")
if data.get("elevationStatus") is None:
    raise SystemExit(f"{sys.argv[2]}: elevationStatus is missing")
if data.get("sceneryEvidenceStatus") is None:
    raise SystemExit(f"{sys.argv[2]}: sceneryEvidenceStatus is missing")
if not isinstance(data.get("aiWorkerMetadata"), dict):
    raise SystemExit(f"{sys.argv[2]}: aiWorkerMetadata is missing")
if not isinstance(data.get("evidenceBadges"), list):
    raise SystemExit(f"{sys.argv[2]}: evidenceBadges is missing")
PY
}

graphhopper_payload() {
  local mode="$1"
  python3 - "$mode" <<'PY'
import json
import sys

payload = {
    "profile": "bike",
    "points": [[126.9527, 37.4812], [126.9894, 37.5499]],
    "points_encoded": False,
    "details": [
        "average_slope",
        "bike_network",
        "max_slope",
        "road_class",
        "road_environment",
        "smoothness",
        "surface",
    ],
}
if sys.argv[1] == "custom":
    payload["custom_model"] = {
        "priority": [
            {"if": "road_class == MOTORWAY", "multiply_by": "0.0"},
            {"if": "bike_network == MISSING", "multiply_by": "0.9"},
        ]
    }
print(json.dumps(payload))
PY
}

ai_route_payload() {
  local text="$1"
  python3 - "$text" <<'PY'
import json
import sys

print(json.dumps({
    "lat": 37.4812,
    "lon": 126.9527,
    "text": sys.argv[1],
}, ensure_ascii=False))
PY
}

on_error() {
  local exit_code="$?"
  write_summary "failed"
  log "failed with exit_code=$exit_code. Evidence: $RESULTS_DIR"
  exit "$exit_code"
}

trap on_error ERR

main() {
  require_command curl
  require_command python3

  if [[ "$VALIDATE_ONLY" == "true" ]]; then
    write_summary "validated"
    log "validated script prerequisites only. Evidence: $RESULTS_DIR"
    return
  fi

  if [[ "$RUN_GRAPHHOPPER_DIRECT" == "true" ]]; then
    log "GraphHopper direct baseline route"
    http_json "graphhopper-baseline" "POST" "${GRAPHHOPPER_URL%/}/route" "$(graphhopper_payload baseline)"
    require_status "graphhopper-baseline" "200"
    assert_graphhopper_route "graphhopper-baseline"

    log "GraphHopper direct custom-model route"
    http_json "graphhopper-custom-model" "POST" "${GRAPHHOPPER_URL%/}/route" "$(graphhopper_payload custom)"
    require_status "graphhopper-custom-model" "200"
    assert_graphhopper_route "graphhopper-custom-model"
  fi

  if [[ "$RUN_OPENAPI_CONTRACT" == "true" ]]; then
    log "OpenAPI AI route metadata fields"
    http_json "openapi" "GET" "${BASE_URL%/}/v3/api-docs" ""
    require_status "openapi" "200"
    assert_openapi_fields
  fi

  if [[ "$RUN_AI_ROUTE_SMOKE" == "true" ]]; then
    log "AI route from text metadata smoke"
    http_json "ai-route-from-text" "POST" "${BASE_URL%/}/api/v1/ai-routes/plan/from-text" \
      "$(ai_route_payload '평지 한강이 보이는 목적지 없는 코스')" \
      -H "X-Guest-Device-Id: ai-route-evidence-smoke-$(date +%s)"
    require_status "ai-route-from-text" "200"
    assert_ai_route_response "ai-route-from-text"
  fi

  write_summary "passed"
  log "AI route evidence smoke passed. Evidence: $RESULTS_DIR"
}

main "$@"
