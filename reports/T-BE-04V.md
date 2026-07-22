# T-BE-04V verification report

## Completed scope

- The deploy state machine creates and validates approved-root state directories through an inline Python `dir_fd` walk using `O_DIRECTORY|O_NOFOLLOW`. Lock, generation, marker, ancestry, and candidate temporary paths are opened with `O_NOFOLLOW` before use.
- Public compensation now applies the same approved-root dirfd/O_NOFOLLOW guard to `.deploy.lock` before shell locking. The production guard requires a root-owned, non-group/world-writable parent; the isolated test harness explicitly enables its unprivileged fixture mode.
- The rendered-command contract covers external-sentinel preservation for final, intermediate, lock, marker, and public-lock symlinks; relative `current.jar` from a controlled process cwd for deploy and public rollback; and wrong-cwd/read-failure paths with no promotion change.
- S3 conditional PutObject maps `PreconditionFailed` to HTTP 412, but AWS CLI stderr does not reliably include the status. Reconciliation therefore anchor-matches only `An error occurred (PreconditionFailed) when calling the PutObject operation:`; lookalike text, another operation's `PreconditionFailed`, AccessDenied, timeout, and reset failures do not call `head-object`. Manifest validation is fail-late and retains a failure verdict.
- Production OIDC is limited to `refs/heads/main`. Deploy, terminal reconciliation, and public rollback SSM commands all have a 600-second remote timeout, a runner poll deadline, `cancel-command`, and bounded terminal-status confirmation. Non-success deploy generations are reconciled under the accepted generation lock; candidate/previous/current/MainPID jar and digests are structured evidence. Public rollback control evidence records command ID and terminal status.

## Final verification

- Command: `./gradlew --no-daemon clean test --tests '*BackendCdWorkflowContractTest' --tests '*AlbHealthCheckContractTest' --tests '*AiRouteContractSmokeTest' --tests '*HealthControllerTest'`
- Result: Backend CD 51/0/0, ALB 1/0/0, AI route smoke 1/0/0, HealthController 4/0/0 — **57/0/0** total.
- XML: Backend CD `2026-07-22T14:19:54.369Z`, SHA-256 `84ce5356c148934a57f1b9a9b872ff961ca18b1b099e30241c85cb5016a78df6`; ALB `2026-07-22T14:19:53.845Z`, `74004d6c2f1e45a2ff21cf5a4482ef0293b51860ba938f91aa75e97d415f5189`; AI `2026-07-22T14:19:28.104Z`, `10d8e4c3af18190f792edfca312bb96f446b94c857171007aabb35fcb70daa48`; Health `2026-07-22T14:19:50.706Z`, `d283f50adfb86226bcc9c026518d094f12665555165097b63c5ee4ebbfdc3e88`.
- `git diff --check` passed.
- Provenance: base commit `c3c1fb6208943841195762458199eadbc9ad7487`; base tree `2a2d967d9221a40eff50207df9a761e2fd1330eb`; workflow SHA-256 `8221c797def32b9ffb2164bc5a925fe3530a3f9ec4477bf3d89b67aeb02b1e63`; contract-test SHA-256 `4610ef20617b4e4de3ac979e9b220eeb86406da2bccca3fc88a375010362046e`.

## Residual risk

All validation is offline. Real AWS/S3/SSM, target DB, service restart, and public endpoint behavior remain environment-specific deployment prerequisites.
