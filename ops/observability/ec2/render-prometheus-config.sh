#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env}"
TEMPLATE_FILE="$ROOT_DIR/prometheus/prometheus.yml.template"
OUTPUT_FILE="$ROOT_DIR/prometheus/prometheus.yml"

if [ ! -f "$ENV_FILE" ]; then
  printf '[ERROR] observability env file not found: %s\n' "$ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${APP_PRIVATE_HOST:?APP_PRIVATE_HOST is required}"

envsubst < "$TEMPLATE_FILE" > "$OUTPUT_FILE"
printf '[INFO] rendered Prometheus config: %s\n' "$OUTPUT_FILE"
