# T-BE-04V verification report

## Completed scope

- The deploy state machine creates and validates approved-root state directories through an inline Python `dir_fd` walk using `O_DIRECTORY|O_NOFOLLOW`. Lock, generation, marker, ancestry, and candidate temporary paths are opened with `O_NOFOLLOW` before use.
- Public compensation now applies the same approved-root dirfd/O_NOFOLLOW guard to `.deploy.lock` before shell locking. The production guard requires a root-owned, non-group/world-writable parent; the isolated test harness explicitly enables its unprivileged fixture mode.
- The rendered-command contract covers external-sentinel preservation for final, intermediate, lock, marker, and public-lock symlinks; relative `current.jar` from a controlled process cwd for deploy and public rollback; and wrong-cwd/read-failure paths with no promotion change.
- S3 conditional PutObject maps `PreconditionFailed` to HTTP 412, but AWS CLI stderr does not reliably include the status. Reconciliation therefore anchor-matches only `An error occurred (PreconditionFailed) when calling the PutObject operation:`; lookalike text, another operation's `PreconditionFailed`, AccessDenied, timeout, and reset failures do not call `head-object`. Manifest validation is fail-late and retains a failure verdict.
- Deploy primary and reconciliation preserve cancel outcomes, complete bounded terminal waits after cancel API failures or persistent non-terminal states, and write `Unconfirmed` plus a deterministic failure control record when reconciliation remains stuck. A non-success or unconfirmed primary always dispatches accepted-generation-locked target reconciliation; control validation derives phase necessity from captured remote evidence and fails closed when either ID or control artifact is absent. Public rollback retains the same bounded reconciliation and late-mutation guard.

## Final verification

- Command: `./gradlew --no-daemon clean test --tests '*BackendCdWorkflowContractTest' --tests '*AlbHealthCheckContractTest' --tests '*AiRouteContractSmokeTest' --tests '*HealthControllerTest'`
- Result: Backend CD 58/0/0, ALB 1/0/0, AI route smoke 1/0/0, HealthController 4/0/0 — **64/0/0** total.
- XML: Backend CD `2026-07-22T16:40:55.352Z`, SHA-256 `2eb7820d9327f95b27f35ef0b1a75fdaee9120b9e9e799968f340076ab37a73d`; ALB `2026-07-22T16:40:54.872Z`, `1e235ae2727b01c1aa1fe51e5cf21534f0e7ae92417901e6fc94fffe25ae773e`; AI `2026-07-22T16:40:27.571Z`, `a0d1f4d97eea7932f8754820fd0933715c8c94be8298d97ecf8bd4725f7a89b6`; Health `2026-07-22T16:40:52.252Z`, `e9ef4910a7f926b09e6e5e98568180893ed8da48ab9ef85b015ba0b9943c8ce0`.
- `git diff --check` passed.
- Provenance: PR #82 reviewed SHA `b5b258444f9202326987207b059ba3b1d9616d28`; inherited base commit `c3c1fb6208943841195762458199eadbc9ad7487`; workflow SHA-256 `5ec197a578c3d50fb665a72933a1fbfa8db6918d30dc658fc031229d0d1c0de1`; contract-test SHA-256 `c3944d85295d4b387aad7a8357d8fd200bafa985f5f11f2e6b648e00f764cbd8`.

## Residual risk

All validation is offline. Real AWS/S3/SSM, target DB, service restart, and public endpoint behavior remain environment-specific deployment prerequisites.
