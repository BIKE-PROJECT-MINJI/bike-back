#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${1:-$ROOT_DIR/.env}"

if [ ! -f "$ENV_FILE" ]; then
  printf '[ERROR] env file not found: %s\n' "$ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${GRAFANA_DOMAIN:?GRAFANA_DOMAIN is required}"
: "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD is required}"

sudo mkdir -p /var/www/certbot

if [ ! -f /etc/nginx/.htpasswd-bike-observability ]; then
  sudo htpasswd -bc /etc/nginx/.htpasswd-bike-observability admin "$GRAFANA_ADMIN_PASSWORD"
fi

sudo cp "$ROOT_DIR/nginx/observability-bootstrap.conf" /etc/nginx/conf.d/observability.conf
sudo nginx -t
sudo systemctl reload nginx

sudo certbot --nginx -d "$GRAFANA_DOMAIN" --non-interactive --agree-tos -m "admin@$GRAFANA_DOMAIN" --redirect

sudo cp "$ROOT_DIR/nginx/observability.conf" /etc/nginx/conf.d/observability.conf
sudo sed -i "s/observability\.gajabike\.shop/$GRAFANA_DOMAIN/g" /etc/nginx/conf.d/observability.conf
sudo sed -i "s#/etc/letsencrypt/live/$GRAFANA_DOMAIN#/etc/letsencrypt/live/$GRAFANA_DOMAIN#g" /etc/nginx/conf.d/observability.conf
sudo nginx -t
sudo systemctl reload nginx

printf '[INFO] domain and TLS setup completed for %s\n' "$GRAFANA_DOMAIN"
