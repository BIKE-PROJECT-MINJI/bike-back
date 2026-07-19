#!/usr/bin/env bash
set -euo pipefail

readonly BOOTSTRAP_DIR='/opt/gaja-run/role'
aws s3 sync "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" "$BOOTSTRAP_DIR/" --only-show-errors
cd "$BOOTSTRAP_DIR"
sha256sum --check SHA256SUMS
source "$BOOTSTRAP_DIR/common.sh"

load_role_images
prepare_secret_dir
fetch_secret redis-password redis.password
chown root:1000 "$SECRET_DIR" "$SECRET_DIR/redis.password"
chmod 0750 "$SECRET_DIR"
chmod 0640 "$SECRET_DIR/redis.password"
{
  printf 'bind 0.0.0.0\nprotected-mode yes\nappendonly yes\nappendfsync everysec\n'
  printf 'requirepass %s\n' "$(<"$SECRET_DIR/redis.password")"
} >"$SECRET_DIR/redis.conf"
chown root:1000 "$SECRET_DIR/redis.conf"
chmod 0640 "$SECRET_DIR/redis.conf"
source "$ROLE_DIR/role.env"

docker volume create gaja-redis-data >/dev/null
docker run -d \
  --name gaja-redis \
  --network host \
  --memory 640m \
  --restart unless-stopped \
  --mount "type=bind,src=$SECRET_DIR/redis.conf,dst=/usr/local/etc/redis/redis.conf,readonly" \
  --mount type=volume,src=gaja-redis-data,dst=/data \
  "$REDIS_IMAGE" redis-server /usr/local/etc/redis/redis.conf

for attempt in $(seq 1 30); do
  if docker exec -i gaja-redis sh -c \
    'IFS= read -r REDISCLI_AUTH; export REDISCLI_AUTH; redis-cli --no-auth-warning ping' \
    <"$SECRET_DIR/redis.password" | grep -qx PONG; then
    mark_bootstrap_ready
    exit 0
  fi
  sleep 2
done

printf 'Redis health did not become ready\n' >&2
exit 1
