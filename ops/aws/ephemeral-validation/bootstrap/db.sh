#!/usr/bin/env bash
set -euo pipefail

readonly BOOTSTRAP_DIR='/opt/gaja-run/role'
aws s3 sync "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" "$BOOTSTRAP_DIR/" --only-show-errors
cd "$BOOTSTRAP_DIR"
sha256sum --check SHA256SUMS
source "$BOOTSTRAP_DIR/common.sh"

load_role_images
prepare_secret_dir
fetch_secret db-password postgres.password
chown root:999 "$SECRET_DIR" "$SECRET_DIR/postgres.password"
chmod 0750 "$SECRET_DIR"
chmod 0640 "$SECRET_DIR/postgres.password"
source "$ROLE_DIR/role.env"

docker volume create gaja-postgres-data >/dev/null
docker run -d \
  --name gaja-postgis \
  --network host \
  --memory 1500m \
  --restart unless-stopped \
  --env POSTGRES_DB=bike \
  --env POSTGRES_USER=bike \
  --env POSTGRES_PASSWORD_FILE=/run/secrets/postgres.password \
  --mount "type=bind,src=$SECRET_DIR,dst=/run/secrets,readonly" \
  --mount type=volume,src=gaja-postgres-data,dst=/var/lib/postgresql/data \
  "$POSTGIS_IMAGE"

for attempt in $(seq 1 60); do
  if docker exec gaja-postgis pg_isready -U bike -d bike >/dev/null 2>&1; then
    mark_bootstrap_ready
    exit 0
  fi
  sleep 5
done

printf 'PostGIS health did not become ready\n' >&2
exit 1
