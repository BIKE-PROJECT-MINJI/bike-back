#!/usr/bin/env bash
set -euo pipefail

readonly BOOTSTRAP_DIR='/opt/gaja-run/role'
aws s3 sync "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" "$BOOTSTRAP_DIR/" --only-show-errors
cd "$BOOTSTRAP_DIR"
sha256sum --check SHA256SUMS
source "$BOOTSTRAP_DIR/common.sh"

load_role_images
prepare_secret_dir
fetch_secret grafana-password grafana.password
chmod 0750 "$SECRET_DIR"
chmod 0640 "$SECRET_DIR/grafana.password"
source "$ROLE_DIR/role.env"
install -d -m 0755 /opt/gaja-run/prometheus
install -d -m 0755 /opt/gaja-run/grafana/provisioning/datasources
cat > /opt/gaja-run/verify-observability.sh <<'SH'
#!/usr/bin/env bash
set -euo pipefail
source /opt/gaja-run/role/role.env
curl -fsS --max-time 5 http://127.0.0.1:9090/-/ready >/dev/null
curl -fsS --max-time 5 http://127.0.0.1:3000/api/health >/dev/null
targets_json="$(curl -fsS --max-time 5 http://127.0.0.1:9090/api/v1/targets)"
healthy_targets="$(grep -o '"health":"up"' <<<"$targets_json" | wc -l || true)"
((healthy_targets >= EXPECTED_APP_TARGETS))
SH
chmod 0700 /opt/gaja-run/verify-observability.sh
cat > /opt/gaja-run/prometheus/prometheus.yml <<'YAML'
global:
  scrape_interval: 5s
scrape_configs:
  - job_name: gaja-app
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['10.88.10.20:18081', '10.88.11.20:18081']
YAML
cat > /opt/gaja-run/grafana/provisioning/datasources/prometheus.yml <<'YAML'
apiVersion: 1
datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://127.0.0.1:9090
    isDefault: true
    editable: false
YAML

docker volume create gaja-prometheus-data >/dev/null
docker volume create gaja-grafana-data >/dev/null
docker run -d \
  --name gaja-prometheus \
  --network host \
  --memory 640m \
  --restart unless-stopped \
  --mount type=bind,src=/opt/gaja-run/prometheus/prometheus.yml,dst=/etc/prometheus/prometheus.yml,readonly \
  --mount type=volume,src=gaja-prometheus-data,dst=/prometheus \
  "$PROMETHEUS_IMAGE" \
  --config.file=/etc/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=6h

docker run -d \
  --name gaja-grafana \
  --network host \
  --memory 640m \
  --restart unless-stopped \
  --env GF_SECURITY_ADMIN_USER=admin \
  --env GF_SECURITY_ADMIN_PASSWORD__FILE=/run/secrets/grafana.password \
  --env GF_USERS_ALLOW_SIGN_UP=false \
  --mount "type=bind,src=$SECRET_DIR,dst=/run/secrets,readonly" \
  --mount type=bind,src=/opt/gaja-run/grafana/provisioning/datasources,dst=/etc/grafana/provisioning/datasources,readonly \
  --mount type=volume,src=gaja-grafana-data,dst=/var/lib/grafana \
  "$GRAFANA_IMAGE"

for attempt in $(seq 1 120); do
  if /opt/gaja-run/verify-observability.sh; then
    mark_bootstrap_ready
    exit 0
  fi
  sleep 5
done

printf 'Prometheus, Grafana, or app scrape target did not become ready\n' >&2
exit 1
