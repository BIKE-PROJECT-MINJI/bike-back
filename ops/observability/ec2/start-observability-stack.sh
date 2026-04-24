#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env}"

"$ROOT_DIR/render-prometheus-config.sh" "$ENV_FILE"

docker compose \
  --env-file "$ENV_FILE" \
  -f "$ROOT_DIR/docker-compose.observability.yml" \
  up -d

printf '[INFO] observability stack started\n'
