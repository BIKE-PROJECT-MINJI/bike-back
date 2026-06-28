#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE_URL="${BIKE_SMOKE_BASE_URL:-http://127.0.0.1:8080}"
RESULTS_DIR="${RESULTS_DIR:-$ROOT_DIR/ops/smoke/results/hybrid-device-smoke-$(date +%Y%m%d-%H%M%S)}"

RUN_ADDRESS_SMOKE="${RUN_ADDRESS_SMOKE:-true}"
RUN_AI_ROUTE_SMOKE="${RUN_AI_ROUTE_SMOKE:-true}"
RUN_RIDE_SUMMARY_SMOKE="${RUN_RIDE_SUMMARY_SMOKE:-true}"
RUN_ACCOUNT_CLEANUP="${RUN_ACCOUNT_CLEANUP:-true}"
VALIDATE_ONLY="${VALIDATE_ONLY:-false}"

REQUEST_ID_PREFIX="${REQUEST_ID_PREFIX:-hybrid-device-smoke}"
EMAIL="${BIKE_SMOKE_EMAIL:-bike-smoke-$(date +%s)@example.com}"
PASSWORD="${BIKE_SMOKE_PASSWORD:-SmokePassword123!}"
DISPLAY_NAME="${BIKE_SMOKE_DISPLAY_NAME:-Hybrid Device Smoke}"
ADDRESS_QUERY="${BIKE_SMOKE_ADDRESS_QUERY:-Seoul City Hall}"
AI_ROUTE_TEXT="${BIKE_SMOKE_AI_ROUTE_TEXT:-flat riverside route to test provider fallback}"

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

urlencode() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import quote

print(quote(sys.argv[1], safe=""))
PY
}

json_value() {
  local file="$1"
  local path="$2"
  python3 - "$file" "$path" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    value = json.load(handle)
for part in sys.argv[2].split("."):
    if isinstance(value, dict):
        value = value.get(part)
    else:
        value = None
        break
if isinstance(value, (dict, list)):
    print(json.dumps(value, ensure_ascii=False))
elif value is not None:
    print(value)
PY
}

assert_api_response() {
  local label="$1"
  local body_file="$RESULTS_DIR/$label.body.json"
  python3 - "$body_file" "$label" <<'PY'
import json
import sys

body_file = sys.argv[1]
label = sys.argv[2]
with open(body_file, "r", encoding="utf-8") as handle:
    payload = json.load(handle)
if not isinstance(payload, dict):
    raise SystemExit(f"{label}: response is not a JSON object")
if payload.get("code") != 200:
    raise SystemExit(f"{label}: expected code=200, got {payload.get('code')}")
if "message" not in payload or "data" not in payload:
    raise SystemExit(f"{label}: expected code/message/data wrapper")
PY
}

redact_auth_body() {
  local label="$1"
  local body_file="$RESULTS_DIR/$label.body.json"
  python3 - "$body_file" <<'PY'
import json
import sys

body_file = sys.argv[1]
with open(body_file, "r", encoding="utf-8") as handle:
    payload = json.load(handle)
data = payload.get("data")
if isinstance(data, dict):
    for key in ("accessToken", "refreshToken"):
        if key in data and data[key]:
            data[key] = "__redacted__"
with open(body_file, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
PY
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

record_step() {
  local label="$1"
  local method="$2"
  local path="$3"
  local status="$4"
  local request_id="$5"
  local trace_id="$6"
  cat >"$RESULTS_DIR/$label.step.json" <<EOF
{
  "label": "$label",
  "method": "$method",
  "path": "$path",
  "status": "$status",
  "requestId": "$request_id",
  "traceId": "$trace_id",
  "bodyFile": "$label.body.json",
  "headersFile": "$label.headers.txt"
}
EOF
}

extract_header() {
  local file="$1"
  local name="$2"
  awk -v header="$name" 'BEGIN {IGNORECASE=1} $0 ~ "^" header ":" {sub("\r$", "", $0); sub("^[^:]+:[[:space:]]*", "", $0); print; exit}' "$file"
}

http_request() {
  local label="$1"
  local method="$2"
  local path="$3"
  local payload="${4:-}"
  shift 4

  local body_file="$RESULTS_DIR/$label.body.json"
  local headers_file="$RESULTS_DIR/$label.headers.txt"
  local status_file="$RESULTS_DIR/$label.status"
  local request_id="$REQUEST_ID_PREFIX-$label-$(date +%s%N)"
  local url="${BASE_URL%/}$path"
  local status

  local -a args
  args=(-sS -X "$method" "$url" -D "$headers_file" -o "$body_file" -w "%{http_code}"
    -H "Accept: application/json"
    -H "X-Request-Id: $request_id")
  if [[ -n "$payload" ]]; then
    args+=(-H "Content-Type: application/json" --data "$payload")
  fi
  args+=("$@")

  log "$method $path"
  status="$(curl "${args[@]}")"
  printf '%s' "$status" >"$status_file"

  local response_request_id
  local trace_id
  response_request_id="$(extract_header "$headers_file" "X-Request-Id" || true)"
  trace_id="$(extract_header "$headers_file" "X-Trace-Id" || true)"
  record_step "$label" "$method" "$path" "$status" "${response_request_id:-$request_id}" "$trace_id"
}

write_summary() {
  local status="$1"
  python3 - "$RESULTS_DIR" "$status" "$BASE_URL" <<'PY'
import json
import pathlib
import sys

results_dir = pathlib.Path(sys.argv[1])
summary_status = sys.argv[2]
base_url = sys.argv[3]
steps = []
for path in sorted(results_dir.glob("*.step.json")):
    with path.open("r", encoding="utf-8") as handle:
        steps.append(json.load(handle))
summary = {
    "status": summary_status,
    "baseUrl": base_url,
    "createdAt": __import__("datetime").datetime.now(__import__("datetime").timezone.utc).isoformat(),
    "steps": steps,
}
with (results_dir / "summary.json").open("w", encoding="utf-8") as handle:
    json.dump(summary, handle, ensure_ascii=False, indent=2)
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

  local encoded_query
  encoded_query="$(urlencode "$ADDRESS_QUERY")"

  http_request "health" "GET" "/health" ""
  require_status "health" "200"

  http_request "courses-list" "GET" "/api/v1/courses?limit=5" ""
  require_status "courses-list" "200"
  assert_api_response "courses-list"

  if [[ "$RUN_ADDRESS_SMOKE" == "true" ]]; then
    http_request "address-search" "GET" "/api/v1/addresses/search?query=$encoded_query&page=1&size=5" ""
    require_status "address-search" "200"
    assert_api_response "address-search"
  fi

  local register_payload
  register_payload="$(python3 - "$EMAIL" "$PASSWORD" "$DISPLAY_NAME" <<'PY'
import json
import sys

print(json.dumps({
    "email": sys.argv[1],
    "password": sys.argv[2],
    "displayName": sys.argv[3],
    "profileImageUrl": None,
    "legacyExternalId": None,
    "inviteCode": None,
}))
PY
)"
  http_request "auth-register" "POST" "/api/v1/auth/register" "$register_payload"
  require_status "auth-register" "200"
  assert_api_response "auth-register"

  local access_token
  access_token="$(json_value "$RESULTS_DIR/auth-register.body.json" "data.accessToken")"
  if [[ -z "$access_token" ]]; then
    echo "auth-register response did not include data.accessToken" >&2
    exit 21
  fi
  redact_auth_body "auth-register"

  local login_payload
  login_payload="$(python3 - "$EMAIL" "$PASSWORD" <<'PY'
import json
import sys

print(json.dumps({"email": sys.argv[1], "password": sys.argv[2]}))
PY
)"
  http_request "auth-login" "POST" "/api/v1/auth/login" "$login_payload"
  require_status "auth-login" "200"
  assert_api_response "auth-login"
  redact_auth_body "auth-login"

  if [[ "$RUN_AI_ROUTE_SMOKE" == "true" ]]; then
    local ai_payload
    ai_payload="$(python3 - "$AI_ROUTE_TEXT" <<'PY'
import json
import sys

print(json.dumps({
    "lat": 37.5665,
    "lon": 126.9780,
    "text": sys.argv[1],
}))
PY
)"
    http_request "ai-route-from-text" "POST" "/api/v1/ai-routes/plan/from-text" "$ai_payload" -H "X-Guest-Device-Id: hybrid-device-smoke"
    require_status "ai-route-from-text" "200"
    assert_api_response "ai-route-from-text"
  fi

  if [[ "$RUN_RIDE_SUMMARY_SMOKE" == "true" ]]; then
    local ride_payload
    ride_payload="$(python3 <<'PY'
import datetime
import json
import time

started_at = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=18)
ended_at = datetime.datetime.now(datetime.timezone.utc)
print(json.dumps({
    "clientRideId": f"hybrid-device-smoke-{int(time.time())}",
    "startedAt": started_at.isoformat(),
    "endedAt": ended_at.isoformat(),
    "summary": {
        "distanceM": 3200,
        "durationSec": 1080,
    },
}))
PY
)"
    http_request "ride-summary-save" "POST" "/api/v1/ride-records/summary" "$ride_payload" -H "Authorization: Bearer $access_token"
    require_status "ride-summary-save" "200"
    assert_api_response "ride-summary-save"
  fi

  if [[ "$RUN_ACCOUNT_CLEANUP" == "true" ]]; then
    http_request "auth-delete-me" "DELETE" "/api/v1/auth/me" "" -H "Authorization: Bearer $access_token"
    require_status "auth-delete-me" "200"
    assert_api_response "auth-delete-me"
  fi

  write_summary "passed"
  log "hybrid device smoke passed. Evidence: $RESULTS_DIR"
}

main "$@"
