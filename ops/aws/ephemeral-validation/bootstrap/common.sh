#!/usr/bin/env bash
set -euo pipefail

readonly ROLE_DIR='/opt/gaja-run/role'
readonly SECRET_DIR='/run/gaja/secrets'

sync_role_artifacts() {
  install -d -m 0700 "$ROLE_DIR"
  aws s3 sync \
    "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" \
    "$ROLE_DIR/" \
    --only-show-errors
  cd "$ROLE_DIR"
  sha256sum --check SHA256SUMS
}

load_role_images() {
  gzip -dc "$ROLE_DIR/images.tar.gz" | docker load
  rm -f "$ROLE_DIR/images.tar.gz"
}

prepare_secret_dir() {
  install -d -m 0700 "$SECRET_DIR"
}

fetch_secret() {
  local parameter_name="$1"
  local target_name="$2"
  aws ssm get-parameter \
    --name "${GAJA_SECRET_PARAMETER_PREFIX}${parameter_name}" \
    --with-decryption \
    --query 'Parameter.Value' \
    --output text >"$SECRET_DIR/$target_name"
  chmod 0600 "$SECRET_DIR/$target_name"
}

mark_bootstrap_ready() {
  touch "/opt/gaja-run/${GAJA_NODE_ROLE}.ready"
}
