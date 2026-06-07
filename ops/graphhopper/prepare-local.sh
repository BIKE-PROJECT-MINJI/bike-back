#!/usr/bin/env sh
set -eu

GRAPHHOPPER_VERSION="${GRAPHHOPPER_VERSION:-11.0}"
OSM_FILE="${GRAPHHOPPER_OSM_FILE:-south-korea-latest.osm.pbf}"
BASE_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DATA_DIR="$BASE_DIR/data"

mkdir -p "$DATA_DIR"

JAR_PATH="$BASE_DIR/graphhopper-web-$GRAPHHOPPER_VERSION.jar"
if [ ! -f "$JAR_PATH" ]; then
  curl -L \
    "https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/$GRAPHHOPPER_VERSION/graphhopper-web-$GRAPHHOPPER_VERSION.jar" \
    -o "$JAR_PATH"
fi

PBF_PATH="$DATA_DIR/$OSM_FILE"
if [ ! -f "$PBF_PATH" ]; then
  curl -L \
    "https://download.geofabrik.de/asia/south-korea-latest.osm.pbf" \
    -o "$PBF_PATH"
fi

printf 'GraphHopper local assets ready:\n'
printf '  jar: %s\n' "$JAR_PATH"
printf '  pbf: %s\n' "$PBF_PATH"
printf '  config: %s\n' "$BASE_DIR/config-bike.yml"
