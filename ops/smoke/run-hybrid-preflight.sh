#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AI_ROUTE_DIR="${AI_ROUTE_DIR:-$ROOT_DIR/../bike-ai-route}"
RESULTS_DIR="${RESULTS_DIR:-$ROOT_DIR/ops/smoke/results/hybrid-preflight-$(date +%Y%m%d-%H%M%S)}"

RUN_COMPOSE_CONFIG="${RUN_COMPOSE_CONFIG:-true}"
RUN_AWS_SCRIPT_SYNTAX="${RUN_AWS_SCRIPT_SYNTAX:-true}"
RUN_BACKEND_TESTS="${RUN_BACKEND_TESTS:-true}"
RUN_AI_ROUTE_TESTS="${RUN_AI_ROUTE_TESTS:-true}"
BACKEND_TEST_SELECTOR="${BACKEND_TEST_SELECTOR:-com.bikeprojectminji.bikeback.ops.GraphHopperReadinessScriptTest}"
UV_PYTHON="${UV_PYTHON:-3.12}"
AI_ROUTE_VENV=""

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

run_shell_step() {
  local name="$1"
  local command="$2"
  log "start $name"
  (cd "$ROOT_DIR" && bash -lc "$command") >"$RESULTS_DIR/$name.log" 2>&1
  log "pass $name"
}

cleanup_ai_route_artifacts() {
  rm -rf \
    ${AI_ROUTE_VENV:+"$AI_ROUTE_VENV"} \
    "$AI_ROUTE_DIR/.pytest_cache" \
    "$AI_ROUTE_DIR/app/__pycache__" \
    "$AI_ROUTE_DIR/tests/__pycache__"
}

write_summary() {
  local status="$1"
  cat >"$RESULTS_DIR/summary.json" <<EOF
{
  "status": "$status",
  "createdAt": "$(date -Iseconds)",
  "rootDir": "$ROOT_DIR",
  "aiRouteDir": "$AI_ROUTE_DIR",
  "backendTestSelector": "$BACKEND_TEST_SELECTOR",
  "composeConfig": "$RUN_COMPOSE_CONFIG",
  "awsScriptSyntax": "$RUN_AWS_SCRIPT_SYNTAX",
  "backendTests": "$RUN_BACKEND_TESTS",
  "aiRouteTests": "$RUN_AI_ROUTE_TESTS"
}
EOF
}

on_error() {
  local exit_code="$?"
  cleanup_ai_route_artifacts
  write_summary "failed"
  log "failed with exit_code=$exit_code. Evidence: $RESULTS_DIR"
  exit "$exit_code"
}

trap on_error ERR

main() {
  cd "$ROOT_DIR"
  require_command bash

  if [[ "$RUN_COMPOSE_CONFIG" == "true" ]]; then
    require_command docker
    run_shell_step "compose-local-config" "docker compose -f docker-compose.local.yml config >/tmp/bike-compose-local.yml"
    run_shell_step "compose-test-config" "docker compose -f docker-compose.test.yml config >/tmp/bike-compose-test.yml"
  fi

  if [[ "$RUN_AWS_SCRIPT_SYNTAX" == "true" ]]; then
    run_shell_step "aws-wrapper-syntax" "bash -n ops/loadtest/run-aws-compose-k6.sh"
  fi

  if [[ "$RUN_BACKEND_TESTS" == "true" ]]; then
    run_shell_step "backend-targeted-test" "./gradlew test --tests '$BACKEND_TEST_SELECTOR'"
  fi

  if [[ "$RUN_AI_ROUTE_TESTS" == "true" ]]; then
    require_command uv
    if [[ ! -d "$AI_ROUTE_DIR" ]]; then
      echo "AI route worker directory not found: $AI_ROUTE_DIR" >&2
      exit 11
    fi
    AI_ROUTE_VENV="$(mktemp -d "${TMPDIR:-/tmp}/bike-ai-route-venv.XXXXXX")"
    run_shell_step "ai-route-pytest" "cd '$AI_ROUTE_DIR' && TMPDIR='${TMPDIR:-/tmp}' UV_LINK_MODE=copy UV_PROJECT_ENVIRONMENT='$AI_ROUTE_VENV' uv run --python '$UV_PYTHON' python -m pytest tests -q"
    cleanup_ai_route_artifacts
    AI_ROUTE_VENV=""
  fi

  write_summary "passed"
  log "hybrid preflight passed. Evidence: $RESULTS_DIR"
}

main "$@"
