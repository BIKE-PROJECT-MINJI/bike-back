#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

export GIT_COMMIT="$(git rev-parse HEAD)"
EVIDENCE_DIR="ops/smoke/public-evidence"
rm -rf build/public-evidence build/test-results/test build/test-results/postgisTest
rm -f "$EVIDENCE_DIR"/*.json

./gradlew test \
  --tests '*GpxTrackParserTest' \
  --tests '*OpenApiRepresentativeContractTest' \
  --tests '*RidePolicyTraceReplayTest' \
  --tests '*AiRouteGoldenSetTest' \
  --tests '*AiRoutePlannerServiceIntegrationTest' \
  postgisTest \
  --tests '*PostgisMigrationContractTest' \
  --tests '*RideRecordPostgresConcurrencyTest'

mkdir -p "$EVIDENCE_DIR"
install -m 0644 build/public-evidence/postgis-contract.json "$EVIDENCE_DIR/postgis-contract.json"
install -m 0644 build/public-evidence/postgres-application-contract.json "$EVIDENCE_DIR/postgres-application-contract.json"
install -m 0644 build/public-evidence/route-policy-replay.json "$EVIDENCE_DIR/route-policy-replay.json"
install -m 0644 build/public-evidence/ai-route-golden-set.json "$EVIDENCE_DIR/ai-route-golden-set.json"

GIT_COMMIT="$GIT_COMMIT" ops/smoke/check-public-evidence.sh "$EVIDENCE_DIR"
