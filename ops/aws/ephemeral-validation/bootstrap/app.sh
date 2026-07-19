#!/usr/bin/env bash
set -euo pipefail

readonly BOOTSTRAP_DIR='/opt/gaja-run/role'
aws s3 sync "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" "$BOOTSTRAP_DIR/" --only-show-errors
cd "$BOOTSTRAP_DIR"
sha256sum --check SHA256SUMS
source "$BOOTSTRAP_DIR/common.sh"

load_role_images
prepare_secret_dir
fetch_secret db-password spring.datasource.password
fetch_secret jwt-secret auth.jwt.secret
fetch_secret redis-password spring.data.redis.password
printf '%s\n' '10.88.10.11' >"$SECRET_DIR/spring.data.redis.host"
printf '%s\n' '6379' >"$SECRET_DIR/spring.data.redis.port"
chmod 0600 \
  "$SECRET_DIR/spring.data.redis.host" \
  "$SECRET_DIR/spring.data.redis.port"

source "$ROLE_DIR/role.env"
docker run -d \
  --name gaja-ai-route \
  --network host \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --memory 256m \
  --restart unless-stopped \
  "$AI_IMAGE"

docker run -d \
  --name gaja-back \
  --network host \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=256m \
  --memory 1152m \
  --restart unless-stopped \
  --env-file "$ROLE_DIR/backend.env" \
  --env SPRING_CONFIG_IMPORT='configtree:/run/secrets/' \
  --mount "type=bind,src=$SECRET_DIR,dst=/run/secrets,readonly" \
  "$BACKEND_IMAGE"

for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8091/health >/dev/null \
    && curl -fsS http://127.0.0.1:8080/health >/dev/null; then
    mark_bootstrap_ready
    exit 0
  fi
  sleep 5
done

printf 'app or AI worker health did not become ready\n' >&2
exit 1
