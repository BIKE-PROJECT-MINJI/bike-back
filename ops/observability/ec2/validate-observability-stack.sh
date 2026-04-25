#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env}"

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

printf '[INFO] checking actuator endpoint\n'
curl -fsS "http://${APP_PRIVATE_HOST}:18081/actuator/prometheus" >/tmp/bike-app-prometheus.txt

printf '[INFO] checking prometheus readiness\n'
curl -fsS http://127.0.0.1:9090/-/ready >/tmp/bike-prometheus-ready.txt

printf '[INFO] checking grafana login page\n'
curl -fsS http://127.0.0.1:3000/login >/tmp/bike-grafana-login.html

printf '[INFO] checking prometheus targets\n'
curl -fsS http://127.0.0.1:9090/api/v1/targets >/tmp/bike-prometheus-targets.json

printf '[INFO] observability validation completed\n'
