# Backend resilience failure/recovery/deployment runbook

Status: **HARNESS READY; FULL DRILL UNVERIFIED**. This runbook never authorizes
AWS/provider/deployment changes. Gate C approval and an isolated non-production
target are required before `--execute` is considered.

## Contract and matrix

`../smoke/resilience-matrix.json` is the machine-checked source for the six
failure scenarios (DB pool, Redis, GraphHopper, AI/Kakao, worker stop, app
restart) and four recovery/deployment scenarios (app/worker lease recovery,
target drain, current/previous jar rollback, Flyway expand-contract old-jar
compatibility). Every row contains precondition, non-destructive injection,
expected fallback/503/retry, forbidden/stop outcomes, evidence, restore, and
cleanup. A raw 500, duplicate/orphan job, data loss, rollback incompatibility,
ALB unhealthy state, or unknown residual resource is an immediate stop.

## Fail-closed preflight

Set non-secret aliases/limits only: `TEST_ID`, `TARGET_ENV`, immutable
`SOURCE_COMMIT_SHA` and `ARTIFACT_SHA256`, typed bounded `BUDGET` as
`requests=N,cost_usd=N.NN`, `TTL_SECONDS`, `CLEANUP_OWNER`,
`CREDENTIAL_OWNER`, `SLO`, `BIKE_BASE_URL`, and `READINESS_URL`. Gate B URLs
must use reserved `.invalid` or `.test` aliases; live/private/AWS hosts and
resource identifiers are rejected. Missing,
malformed, secret-like, production, or unbounded values stop before any command
lookup. Values are decisions supplied by Gate C; this runbook does not invent
them.

The application currently has no public `/ready` route. `READINESS_URL` must be
an explicitly approved dependency-gate mapping (for example the protected
monitoring endpoint); it must never silently reuse liveness `/health`. Live
readiness remains **UNVERIFIED** until that mapping is approved and exercised.

Default planning is external-command-free:

```sh
./ops/smoke/run-resilience-drill.sh
./ops/smoke/run-resilience-drill.sh -- aws elbv2 describe-target-health
```

Gate B has no subprocess runner. `--execute` always fails, stale/replayed
approval values have no effect, and even the read-only describe plan accepts no
target/profile/endpoint/resource arguments. ALB registration, `systemctl`,
Terraform, database/Flyway mutation, provider calls, arbitrary shell, jar
switching, and deletion are not implemented. A full executor is deferred to a
Gate C design binding action, target, expiry, nonce, baseline, bounded mutation,
continuous stop monitoring, `finally` restoration, and an independent cleanup
verifier.

## Drill sequence

1. Record baseline artifact checksum, health, approved readiness, ALB target
   count, DB row/hash assertions, job/lease counts, and cleanup owner/TTL.
2. Print the selected matrix row and action. Confirm restore and cleanup can be
   performed idempotently before approval.
3. If Gate C approves, exercise one isolated scenario only. Stop immediately on
   any matrix `forbidden` outcome or UNKNOWN evidence.
4. Restore before expanding scope. App/worker restart must prove expired
   `RUNNING` lease reclaim and exactly-once convergence; target drain must retain
   healthy capacity; jar rollback must verify both checksums; Flyway must be
   expand-only and old-jar compatible without `repair`.
5. Validate evidence bytes against the exact typed allowlist schema, then produce
   a redacted manifest with command name only, exit code, UTC timestamp,
   test/environment aliases, redaction state, artifact/evidence SHA-256,
   DB/job assertions, health/readiness, and cleanup receipt. Never store command
   arguments, environment dumps, credentials, URLs with userinfo, raw tokens,
   private keys, or location traces.
6. Final PASS additionally requires command exit 0, liveness 200, approved
   readiness 200, every stop flag false, explicit normal DB/job assertions, and
   cleanup `VERIFIED`; 503, PENDING, missing, or UNKNOWN is STOP. Cleanup is
   complete only when target/process/config state equals baseline,
   duplicate/orphan counts are zero, lease/backlog converges, and the owner emits
   `VERIFIED`. UNKNOWN cleanup overrides an otherwise successful drill.

## Restoration boundaries

- DB/Redis/provider injection changes only isolated process configuration; it
  never changes shared SGs or live services without Gate C.
- Worker/app restoration uses the same immutable artifact and original process
  count. A target is registered only after liveness and approved readiness pass.
- Jar rollback is an atomic current/previous switch only after both SHA-256 and
  expanded-schema compatibility are proven. Checksum mismatch is NO-GO.
- Flyway drill uses a disposable production-like snapshot and
  `validate`/`info`/compatibility probes. `migrate`, `repair`, reset, destructive
  contract DDL, and production backup restoration are outside this harness.

No full AWS/provider/failure/recovery/deployment drill has been run by this
change; all live outcomes remain **UNVERIFIED**.
