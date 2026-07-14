#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${1:-ops/smoke/public-evidence}"

if [[ ! -d "$TARGET_DIR" ]]; then
  printf 'evidence directory not found: %s\n' "$TARGET_DIR" >&2
  exit 1
fi

SECRET_PATTERN='(AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|sk-[A-Za-z0-9_-]{16,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|jdbc:postgresql://[^[:space:]]+:[^[:space:]@]+@|"(api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)"[[:space:]]*:[[:space:]]*"[^"[:space:]]{8,}")'

if rg --line-number --pcre2 --ignore-case "$SECRET_PATTERN" "$TARGET_DIR"; then
  printf 'redaction check failed: secret-like value found\n' >&2
  exit 1
fi

for evidence in "$TARGET_DIR"/*.json; do
  if ! rg --quiet 'synthetic|deterministic fake provider' "$evidence"; then
    printf 'redaction check failed: fixture limitation missing in %s\n' "$evidence" >&2
    exit 1
  fi
done

python3 - "$TARGET_DIR" "${GIT_COMMIT:-}" <<'PY'
import json
import pathlib
import re
import sys

target = pathlib.Path(sys.argv[1])
expected_commit = sys.argv[2]
required = {
    "postgis-contract.json",
    "postgres-application-contract.json",
    "route-policy-replay.json",
    "ai-route-golden-set.json",
}
actual = {path.name for path in target.glob("*.json")}
if actual != required:
    raise SystemExit(f"evidence integrity failed: expected {sorted(required)}, got {sorted(actual)}")

documents = {name: json.loads((target / name).read_text(encoding="utf-8")) for name in required}
commits = {document.get("commit") for document in documents.values()}
if len(commits) != 1 or not re.fullmatch(r"[0-9a-f]{40}", next(iter(commits), "")):
    raise SystemExit(f"evidence integrity failed: inconsistent commit values {commits}")
evidence_commit = next(iter(commits))
if expected_commit and evidence_commit != expected_commit:
    raise SystemExit(f"evidence integrity failed: commit {evidence_commit} != {expected_commit}")
for name, document in documents.items():
    metadata = ("testId", "commit", "executedAt", "environment", "command", "result")
    missing_metadata = [field for field in metadata if not document.get(field)]
    if missing_metadata or document.get("result") != "PASS":
        raise SystemExit(f"evidence integrity failed: {name} metadata/result {missing_metadata}")

postgis = documents["postgis-contract.json"]
if postgis.get("migrationPassed") is not True or postgis.get("geometryColumnsVerified", 0) < 1 \
        or postgis.get("gistIndexesVerified", 0) < 1 or postgis.get("routeLinePointCount", 0) < 2:
    raise SystemExit("evidence integrity failed: PostGIS contract PASS fields missing")

application = documents["postgres-application-contract.json"]
required_true = (
    "gpxImportWithGeometryPassed",
    "invalidGpxRejectedWithoutRows",
    "gpxMidWriteRollbackPassed",
    "crossOwnerAccessDenied",
    "aiCandidateConcurrentPromotePassed",
    "aiCandidateRollbackPassed",
    "ridePolicyApiPostgisPassed",
)
missing = [field for field in required_true if application.get(field) is not True]
if missing or application.get("concurrentRequests") != 10 or application.get("rideRecordRows") != 1 \
        or application.get("routePointRows") != 3 or application.get("finalizationJobRows") != 1:
    raise SystemExit(f"evidence integrity failed: application contract fields {missing}")

replay = documents["route-policy-replay.json"]
if replay.get("passedCases") != 9 or len(replay.get("results", [])) != 9:
    raise SystemExit("evidence integrity failed: route replay is not 9/9")
replay_expected = {
    "RP-01": ("ON_ROUTE", "WITHIN_ROUTE_THRESHOLD", "IN_PROGRESS", "COVERAGE_BELOW_THRESHOLD", 0),
    "RP-02": ("CANDIDATE", "OFF_ROUTE_CANDIDATE_ACTIVE", "IN_PROGRESS", "COVERAGE_BELOW_THRESHOLD", 0),
    "RP-03": ("WARNING", "OFF_ROUTE_WARNING_ACTIVE", "IN_PROGRESS", "COVERAGE_BELOW_THRESHOLD", 0),
    "RP-04": ("ON_ROUTE", "RECOVERED_WITHIN_THRESHOLD", "IN_PROGRESS", "COVERAGE_BELOW_THRESHOLD", 0),
    "RP-05": ("UNDETERMINED", "LOCATION_LOW_ACCURACY", "UNDETERMINED", "LOCATION_LOW_ACCURACY", None),
    "RP-06": ("UNDETERMINED", "LOCATION_STALE", "UNDETERMINED", "LOCATION_STALE", None),
    "RP-07": ("ON_ROUTE", "WITHIN_ROUTE_THRESHOLD", "IN_PROGRESS", "COVERAGE_BELOW_THRESHOLD", 79),
    "RP-08": ("ON_ROUTE", "WITHIN_ROUTE_THRESHOLD", "ELIGIBLE", "NON_LOOP_COMPLETION_READY", 86),
    "RP-09": ("ON_ROUTE", "WITHIN_ROUTE_THRESHOLD", "ELIGIBLE", "LOOP_COMPLETION_READY", 100),
}
replay_actual = {
    item.get("testId"): (
        item.get("offRouteStatus"),
        item.get("offRouteReasonCode"),
        item.get("completionStatus"),
        item.get("completionReasonCode"),
        item.get("coveragePercent"),
    )
    for item in replay.get("results", [])
}
if replay_actual != replay_expected:
    raise SystemExit("evidence integrity failed: route replay semantic values changed")

golden = documents["ai-route-golden-set.json"]
results = golden.get("results", [])
if golden.get("caseCount") != 13 or len(results) != 13:
    raise SystemExit("evidence integrity failed: AI golden set is not 13 cases")
if any(result.get("resultStatus") == "UNEXPECTED_SUCCESS" for result in results):
    raise SystemExit("evidence integrity failed: AI golden set contains UNEXPECTED_SUCCESS")
golden_expected = {
    **{f"AI-{index:02d}": ("READY", 200, 1) for index in range(1, 11)},
    "AI-11": ("BAD_REQUEST", 400, 1),
    "AI-12": ("BAD_REQUEST", 400, 1),
    "AI-13": ("TOO_MANY_REQUESTS", 429, 0),
}
golden_actual = {
    item.get("testId"): (
        item.get("resultStatus"),
        item.get("expectedHttpStatus"),
        item.get("providerCallCount"),
    )
    for item in results
}
if golden_actual != golden_expected:
    raise SystemExit("evidence integrity failed: AI golden semantic values changed")

print(f"evidence integrity passed: commit={evidence_commit}")
PY

printf 'redaction and integrity check passed: %s\n' "$TARGET_DIR"
