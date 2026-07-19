#!/usr/bin/env bash
set -euo pipefail

readonly BOOTSTRAP_DIR='/opt/gaja-run/role'
aws s3 sync "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" "$BOOTSTRAP_DIR/" --only-show-errors
cd "$BOOTSTRAP_DIR"
sha256sum --check SHA256SUMS
source "$BOOTSTRAP_DIR/common.sh"

load_role_images
source "$ROLE_DIR/role.env"
install -d -m 0755 /opt/gaja-run/graphhopper
tar -xzf "$ROLE_DIR/graphhopper-assets.tar.gz" -C /opt/gaja-run/graphhopper
rm -f "$ROLE_DIR/graphhopper-assets.tar.gz"

if ! swapon --show=NAME --noheadings | grep -q .; then
  dd if=/dev/zero of=/opt/gaja-run/graphhopper.swap bs=1M count=1024 status=none
  chmod 0600 /opt/gaja-run/graphhopper.swap
  mkswap /opt/gaja-run/graphhopper.swap >/dev/null
  swapon /opt/gaja-run/graphhopper.swap
fi

docker run -d \
  --name gaja-graphhopper \
  --network host \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=128m \
  --memory 1600m \
  --memory-swap 2300m \
  --restart unless-stopped \
  --mount type=bind,src=/opt/gaja-run/graphhopper,dst=/graphhopper \
  "$GRAPHHOPPER_IMAGE" \
  java -Xmx1050m \
    -Ddw.graphhopper.datareader.file=/graphhopper/data/south-korea-latest.osm.pbf \
    -Ddw.graphhopper.graph.location=/graphhopper/data/graph-cache \
    -jar /graphhopper/graphhopper-web-11.0.jar \
    server /graphhopper/config-bike.yml

for attempt in $(seq 1 120); do
  if curl -fsS --max-time 10 \
    'http://127.0.0.1:8989/route?profile=bike&point=37.481247,126.952739&point=37.551200,126.988200&points_encoded=false&elevation=true' \
    >/dev/null; then
    mark_bootstrap_ready
    exit 0
  fi
  sleep 5
done

printf 'GraphHopper health did not become ready\n' >&2
exit 1
