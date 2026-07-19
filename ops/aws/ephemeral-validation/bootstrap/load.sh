#!/usr/bin/env bash
set -euo pipefail

readonly BOOTSTRAP_DIR='/opt/gaja-run/role'
aws s3 sync "s3://${GAJA_ARTIFACT_BUCKET}/${GAJA_ARTIFACT_PREFIX}/" "$BOOTSTRAP_DIR/" --only-show-errors
cd "$BOOTSTRAP_DIR"
sha256sum --check SHA256SUMS
source "$BOOTSTRAP_DIR/common.sh"

load_role_images
tar -xzf "$ROLE_DIR/k6-scenarios.tar.gz" -C /opt/gaja-run
rm -f "$ROLE_DIR/k6-scenarios.tar.gz"
mark_bootstrap_ready
