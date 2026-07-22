# T-BE-04V verification report

## Completed scope

- The deploy state machine creates and validates approved-root state directories through an inline Python `dir_fd` walk using `O_DIRECTORY|O_NOFOLLOW`. Lock, generation, marker, ancestry, and candidate temporary paths are opened with `O_NOFOLLOW` before use.
- Public compensation now applies the same approved-root dirfd/O_NOFOLLOW guard to `.deploy.lock` before shell locking. The production guard requires a root-owned, non-group/world-writable parent; the isolated test harness explicitly enables its unprivileged fixture mode.
- The rendered-command contract covers external-sentinel preservation for final, intermediate, lock, marker, and public-lock symlinks; relative `current.jar` from a controlled process cwd for deploy and public rollback; and wrong-cwd/read-failure paths with no promotion change.
- S3 conditional PutObject maps `PreconditionFailed` to HTTP 412, but AWS CLI stderr does not reliably include the status. Reconciliation therefore anchor-matches only `An error occurred (PreconditionFailed) when calling the PutObject operation:`; lookalike text, another operation's `PreconditionFailed`, AccessDenied, timeout, and reset failures do not call `head-object`. Manifest validation is fail-late and retains a failure verdict.
- Production OIDC is limited to `refs/heads/main`. Public rollback timeouts preserve the cancel API outcome, complete a bounded terminal wait even when cancellation fails, then invoke the accepted-generation-locked target reconciliation; delayed public rollback documents reject an existing reconciliation marker. Primary and reconciliation command IDs, terminal statuses, cancel outcomes, and target evidence are structured and preserved.

## Final verification

- Command: `./gradlew --no-daemon clean test --tests '*BackendCdWorkflowContractTest' --tests '*AlbHealthCheckContractTest' --tests '*AiRouteContractSmokeTest' --tests '*HealthControllerTest'`
- Result: Backend CD 52/0/0, ALB 1/0/0, AI route smoke 1/0/0, HealthController 4/0/0 — **58/0/0** total.
- XML: Backend CD `2026-07-22T16:00:05.393Z`, SHA-256 `bf9fa8087255c9458ad3988c3902a74a3d956e5bc2b158e27c12d378c53a496c`; ALB `2026-07-22T16:00:05.017Z`, `372367ff3531d497eee49004e804244c30b490f0eea808f437cea696d63bc2ae`; AI `2026-07-22T15:59:44.318Z`, `cdd815d72a72bbfe71d0c18de0c2f3ebfe74f37c0d311e165719009ccfc26c2d`; Health `2026-07-22T16:00:03.310Z`, `534d4722f1833534a3c4590532ab845af240f9bdc29ef87d067970d27da89fbb`.
- `git diff --check` passed.
- Provenance: PR #82 starting SHA `13b2fe677962129908edea68535825d914648ae2`; inherited base commit `c3c1fb6208943841195762458199eadbc9ad7487`; workflow SHA-256 `4083903c9eb94eb815fac265041f530d0afc88ed9005230fa833091912bc61d1`; contract-test SHA-256 `a293b227730dafe52f910199b0df478ee1e04ed3c8df19aaf01555059359d494`.

## Residual risk

All validation is offline. Real AWS/S3/SSM, target DB, service restart, and public endpoint behavior remain environment-specific deployment prerequisites.
