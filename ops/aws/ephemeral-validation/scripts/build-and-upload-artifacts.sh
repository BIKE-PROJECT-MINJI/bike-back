#!/usr/bin/env bash
set -euo pipefail

readonly ALLOW_AWS_APPLY="${ALLOW_AWS_APPLY:-NO}"
[[ "$ALLOW_AWS_APPLY" == "YES" ]] || {
  printf 'set ALLOW_AWS_APPLY=YES after preflight passes\n' >&2
  exit 1
}

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly BACKEND_REPO="$(cd "$STACK_DIR/../../.." && pwd)"
readonly AI_REPO="${AI_REPO:-/mnt/e/bike-work/bike/dev/bike-ai-route}"
readonly GRAPHHOPPER_SOURCE_DIR="${GRAPHHOPPER_SOURCE_DIR:-/mnt/e/bike-work/bike/dev/bike-back/ops/graphhopper}"
readonly GRAPHHOPPER_SOURCE_REPO="${GRAPHHOPPER_SOURCE_REPO:-/mnt/e/bike-work/bike/dev/bike-back}"
readonly TFVARS="$STACK_DIR/terraform.auto.tfvars.json"

read_tfvar() {
  python3 - "$TFVARS" "$1" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    payload = json.load(source)
print(payload[sys.argv[2]])
PY
}

readonly RUN_ID="$(read_tfvar run_id)"
readonly AWS_REGION="$(read_tfvar aws_region)"
readonly ARTIFACT_BUCKET="$(read_tfvar artifact_bucket_name)"
readonly APP_COUNT="$(read_tfvar app_count)"
readonly BACKEND_COMMIT="$(git -C "$BACKEND_REPO" rev-parse HEAD)"
readonly AI_COMMIT="$(git -C "$AI_REPO" rev-parse HEAD)"
readonly GRAPHHOPPER_SOURCE_COMMIT="$(git -C "$GRAPHHOPPER_SOURCE_REPO" rev-parse HEAD)"
readonly BACKEND_IMAGE="gaja-back:${BACKEND_COMMIT}"
readonly AI_IMAGE="gaja-ai-route:${AI_COMMIT}"
readonly POSTGIS_IMAGE='postgis/postgis:16-3.4'
readonly REDIS_IMAGE='redis:7.4.2-alpine'
readonly GRAPHHOPPER_IMAGE='eclipse-temurin:17-jre'
readonly K6_IMAGE='grafana/k6:1.4.1'
readonly PROMETHEUS_IMAGE='prom/prometheus:v3.5.0'
readonly GRAFANA_IMAGE='grafana/grafana-oss:12.1.1'
readonly ARTIFACT_ROOT="$STACK_DIR/.artifacts/$RUN_ID"

for command in aws docker gzip python3 rg sha256sum tar; do
  command -v "$command" >/dev/null || {
    printf 'missing required command: %s\n' "$command" >&2
    exit 1
  }
done

[[ -f "$GRAPHHOPPER_SOURCE_DIR/graphhopper-web-11.0.jar" ]] || {
  printf 'GraphHopper offline assets are missing under %s\n' "$GRAPHHOPPER_SOURCE_DIR" >&2
  exit 1
}
git -C "$GRAPHHOPPER_SOURCE_REPO" diff --quiet HEAD -- \
  ops/graphhopper/config-bike.yml \
  ops/graphhopper/bike_project_bike.json \
  ops/graphhopper/bike_project_elevation.json || {
  printf 'GraphHopper tracked configuration must be clean before artifact build\n' >&2
  exit 1
}
[[ -z "$(git -C "$AI_REPO" status --porcelain)" ]] || {
  printf 'AI repository must be clean before artifact build\n' >&2
  exit 1
}
git -C "$BACKEND_REPO" diff --quiet HEAD -- \
  src/main build.gradle settings.gradle gradle.properties Dockerfile.test-runtime || {
  printf 'backend runtime inputs must be committed before artifact build\n' >&2
  exit 1
}

rm -rf "$ARTIFACT_ROOT"
install -d -m 0700 "$ARTIFACT_ROOT"

"$BACKEND_REPO/gradlew" -p "$BACKEND_REPO" bootJar
docker build -f "$BACKEND_REPO/Dockerfile.test-runtime" -t "$BACKEND_IMAGE" "$BACKEND_REPO"
docker build -t "$AI_IMAGE" "$AI_REPO"
for image in \
  "$POSTGIS_IMAGE" \
  "$REDIS_IMAGE" \
  "$GRAPHHOPPER_IMAGE" \
  "$K6_IMAGE" \
  "$PROMETHEUS_IMAGE" \
  "$GRAFANA_IMAGE"; do
  docker pull "$image"
done

prepare_role() {
  local role="$1"
  install -d -m 0700 "$ARTIFACT_ROOT/$role"
  install -m 0700 "$STACK_DIR/bootstrap/common.sh" "$ARTIFACT_ROOT/$role/common.sh"
  install -m 0700 "$STACK_DIR/bootstrap/$role.sh" "$ARTIFACT_ROOT/$role/bootstrap.sh"
}

save_images() {
  local role="$1"
  shift
  docker save "$@" | gzip -1 >"$ARTIFACT_ROOT/$role/images.tar.gz"
}

for role in app db redis graphhopper load observability; do
  prepare_role "$role"
done

save_images app "$BACKEND_IMAGE" "$AI_IMAGE"
cat >"$ARTIFACT_ROOT/app/role.env" <<EOF
BACKEND_IMAGE='$BACKEND_IMAGE'
AI_IMAGE='$AI_IMAGE'
EOF
cat >"$ARTIFACT_ROOT/app/backend.env" <<'EOF'
SPRING_DATASOURCE_URL=jdbc:postgresql://10.88.10.10:5432/bike
SPRING_DATASOURCE_USERNAME=bike
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20
SPRING_DATA_REDIS_CONNECT_TIMEOUT=400ms
SPRING_DATA_REDIS_COMMAND_TIMEOUT=400ms
AUTH_JWT_ISSUER=gaja-aws-validation
AUTH_JWT_TOKEN_VALIDITY_SEC=900
PORT=8080
MANAGEMENT_PORT=18081
MANAGEMENT_SERVER_ADDRESS=0.0.0.0
MANAGEMENT_PROMETHEUS_PUBLIC_SCRAPE_ENABLED=true
ADDRESS_SEARCH_PROVIDER=fake
WEATHER_PROVIDER=fake
BICYCLE_ROUTING_PROVIDER=graphhopper
BICYCLE_ROUTING_FAKE_ENABLED=false
GRAPHHOPPER_BASE_URL=http://10.88.10.12:8989
AI_ROUTE_WORKER_BASE_URL=http://127.0.0.1:8091
BIKE_RATE_LIMIT_STORE=redis
SPRING_FLYWAY_ENABLED=true
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
BIKE_OBSERVABILITY_HTTP_LOG_MODE=SLOW_OR_ERROR
BIKE_OBSERVABILITY_OPERATION_LOG_MODE=SLOW_OR_ERROR
EOF

save_images db "$POSTGIS_IMAGE"
printf "POSTGIS_IMAGE='%s'\n" "$POSTGIS_IMAGE" >"$ARTIFACT_ROOT/db/role.env"

save_images redis "$REDIS_IMAGE"
printf "REDIS_IMAGE='%s'\n" "$REDIS_IMAGE" >"$ARTIFACT_ROOT/redis/role.env"

save_images graphhopper "$GRAPHHOPPER_IMAGE"
printf "GRAPHHOPPER_IMAGE='%s'\n" "$GRAPHHOPPER_IMAGE" >"$ARTIFACT_ROOT/graphhopper/role.env"
tar -czf "$ARTIFACT_ROOT/graphhopper/graphhopper-assets.tar.gz" \
  -C "$GRAPHHOPPER_SOURCE_DIR" \
  config-bike.yml \
  bike_project_bike.json \
  bike_project_elevation.json \
  graphhopper-web-11.0.jar \
  data/graph-cache \
  data/south-korea-latest.osm.pbf

save_images load "$K6_IMAGE"
printf "K6_IMAGE='%s'\n" "$K6_IMAGE" >"$ARTIFACT_ROOT/load/role.env"
tar -czf "$ARTIFACT_ROOT/load/k6-scenarios.tar.gz" \
  -C "$BACKEND_REPO/ops/loadtest" \
  k6

save_images observability "$PROMETHEUS_IMAGE" "$GRAFANA_IMAGE"
cat >"$ARTIFACT_ROOT/observability/role.env" <<EOF
PROMETHEUS_IMAGE='$PROMETHEUS_IMAGE'
GRAFANA_IMAGE='$GRAFANA_IMAGE'
EXPECTED_APP_TARGETS='$APP_COUNT'
EOF

for role in app db redis graphhopper load observability; do
  (
    cd "$ARTIFACT_ROOT/$role"
    sha256sum bootstrap.sh >bootstrap.sh.sha256
    find . -type f ! -name SHA256SUMS -print0 \
      | sort -z \
      | xargs -0 sha256sum >SHA256SUMS
  )
done

docker image inspect \
  "$BACKEND_IMAGE" "$AI_IMAGE" "$POSTGIS_IMAGE" "$REDIS_IMAGE" \
  "$GRAPHHOPPER_IMAGE" "$K6_IMAGE" "$PROMETHEUS_IMAGE" "$GRAFANA_IMAGE" \
  --format '{{json .RepoDigests}}' >"$ARTIFACT_ROOT/image-digests.txt"

sha256sum \
  "$GRAPHHOPPER_SOURCE_DIR/config-bike.yml" \
  "$GRAPHHOPPER_SOURCE_DIR/bike_project_bike.json" \
  "$GRAPHHOPPER_SOURCE_DIR/bike_project_elevation.json" \
  "$GRAPHHOPPER_SOURCE_DIR/graphhopper-web-11.0.jar" \
  "$GRAPHHOPPER_SOURCE_DIR/data/south-korea-latest.osm.pbf" \
  >"$ARTIFACT_ROOT/graphhopper-asset-sha256.txt"
find "$GRAPHHOPPER_SOURCE_DIR/data/graph-cache" -type f -print0 \
  | sort -z \
  | xargs -0 sha256sum \
  >"$ARTIFACT_ROOT/graphhopper-cache-sha256.txt"

python3 - \
  "$ARTIFACT_ROOT" \
  "$GRAPHHOPPER_SOURCE_DIR" \
  "$BACKEND_COMMIT" \
  "$GRAPHHOPPER_SOURCE_COMMIT" \
  "$(docker image inspect "$BACKEND_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$AI_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$POSTGIS_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$REDIS_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$GRAPHHOPPER_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$K6_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$PROMETHEUS_IMAGE" --format '{{.Size}}')" \
  "$(docker image inspect "$GRAFANA_IMAGE" --format '{{.Size}}')" <<'PY'
import json
import sys
from pathlib import Path

artifact_root = Path(sys.argv[1])
graphhopper_source = Path(sys.argv[2])
backend_commit = sys.argv[3]
graphhopper_source_commit = sys.argv[4]
image_sizes = [int(value) for value in sys.argv[5:]]

def tree_size(path: Path) -> int:
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())

role_image_sizes = {
    "app": image_sizes[0] + image_sizes[1],
    "db": image_sizes[2],
    "redis": image_sizes[3],
    "graphhopper": image_sizes[4],
    "load": image_sizes[5],
    "observability": image_sizes[6] + image_sizes[7],
}
volume_gib = {
    "app": 16,
    "db": 20,
    "redis": 8,
    "graphhopper": 20,
    "load": 8,
    "observability": 16,
}
reserve_bytes = 4 * 1024**3
graphhopper_extract_bytes = tree_size(graphhopper_source)
roles: dict[str, dict[str, int | bool]] = {}
for role, image_bytes in role_image_sizes.items():
    artifact_bytes = tree_size(artifact_root / role)
    extracted_bytes = graphhopper_extract_bytes if role == "graphhopper" else 0
    peak_bytes = reserve_bytes + artifact_bytes + image_bytes + extracted_bytes
    limit_bytes = int(volume_gib[role] * 1024**3 * 0.70)
    roles[role] = {
        "artifact_bytes": artifact_bytes,
        "image_bytes": image_bytes,
        "extracted_bytes": extracted_bytes,
        "peak_bytes": peak_bytes,
        "seventy_percent_limit_bytes": limit_bytes,
        "pass": peak_bytes <= limit_bytes,
    }

payload = {
    "backend_commit": backend_commit,
    "graphhopper_source_commit": graphhopper_source_commit,
    "minimum_free_percent": 30,
    "roles": roles,
    "pass": all(bool(role["pass"]) for role in roles.values()),
}
target = artifact_root / "disk-budget.json"
target.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
if not payload["pass"]:
    raise SystemExit(f"role disk budget failed; inspect {target}")
PY

if rg -n \
  -e 'AIza[0-9A-Za-z_-]{20,}' \
  -e 'AKIA[0-9A-Z]{16}' \
  -e '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----' \
  "$ARTIFACT_ROOT" \
  --glob '*.sh' --glob '*.env' --glob '*.txt'; then
  printf 'secret-like value detected in offline artifact metadata\n' >&2
  exit 1
fi

aws s3 sync \
  "$ARTIFACT_ROOT/" \
  "s3://$ARTIFACT_BUCKET/runs/$RUN_ID/" \
  --region "$AWS_REGION" \
  --sse AES256 \
  --only-show-errors

printf 'artifacts_uploaded run_id=%s backend_commit=%s ai_commit=%s graphhopper_source_commit=%s bucket=%s\n' \
  "$RUN_ID" "$BACKEND_COMMIT" "$AI_COMMIT" "$GRAPHHOPPER_SOURCE_COMMIT" "$ARTIFACT_BUCKET"
