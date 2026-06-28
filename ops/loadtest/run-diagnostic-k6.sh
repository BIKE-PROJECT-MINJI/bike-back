#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
MANAGEMENT_URL="${MANAGEMENT_URL:-http://localhost:18081}"
SCENARIO="${SCENARIO:-smoke}"
TEST_ID="${TEST_ID:-bike-diagnostic-${SCENARIO}-$(date +%Y%m%d-%H%M%S)}"
SUMMARY_DIR="${SUMMARY_DIR:-ops/loadtest/results}"
STDOUT_PATH="$SUMMARY_DIR/$TEST_ID-stdout.log"
SUMMARY_PATH="$SUMMARY_DIR/$TEST_ID-summary.json"
PROMETHEUS_PATH="$SUMMARY_DIR/$TEST_ID-prometheus-after.txt"
REPORT_PATH="$SUMMARY_DIR/$TEST_ID-readable-report.md"

mkdir -p "$SUMMARY_DIR"

if ! command -v k6 >/dev/null 2>&1; then
  echo "missing k6 command" >&2
  exit 10
fi
if ! command -v node >/dev/null 2>&1; then
  echo "missing node command" >&2
  exit 11
fi

echo "test_id=$TEST_ID"
echo "base_url=$BASE_URL"
echo "scenario=$SCENARIO"
echo "summary_path=$SUMMARY_PATH"

set +e
BASE_URL="$BASE_URL" \
SCENARIO="$SCENARIO" \
TEST_ID="$TEST_ID" \
SUMMARY_DIR="$SUMMARY_DIR" \
k6 run ops/loadtest/k6/bike-api.js 2>&1 | tee "$STDOUT_PATH"
k6_status="${PIPESTATUS[0]}"
set -e

if curl -fsS "$MANAGEMENT_URL/actuator/prometheus" -o "$PROMETHEUS_PATH"; then
  echo "prometheus_after=$PROMETHEUS_PATH"
else
  rm -f "$PROMETHEUS_PATH"
  echo "prometheus_after=unavailable"
fi

if [[ -f "$SUMMARY_PATH" ]]; then
  report_args=(--summary "$SUMMARY_PATH" --stdout "$STDOUT_PATH" --output "$REPORT_PATH")
  if [[ -f "$PROMETHEUS_PATH" ]]; then
    report_args+=(--metrics "$PROMETHEUS_PATH")
  fi
  node ops/loadtest/generate-readable-report.mjs "${report_args[@]}"
fi

exit "$k6_status"
