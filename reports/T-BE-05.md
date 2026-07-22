# T-BE-05 결과

- A-T-BE-05-03: `recoveryAllowed`의 초기값을 `autoStartup` 설정에서 가져오도록 변경했다. 따라서 `bike.party.redis.auto-start=false`인 컨텍스트에서는 scheduled recovery가 명시적 `start()` 전까지 Redis container를 시작하거나 listener를 등록하지 않는다.
- 명시적 `start()`는 recovery를 활성화하고 구독을 등록한다. 구독 실패 뒤 `recoverIfNecessary()`는 다시 구독하며, `stop()` 뒤에는 recovery가 비활성화되어 재구독하지 않는다.
- 회귀 테스트는 auto-start false의 inert recovery와 explicit start/recovery/stop lifecycle을 직접 검증한다.
- 집중 검증: `./gradlew --no-daemon test --tests 'com.bikeprojectminji.bikeback.party.websocket.*' --console=plain` 성공, XML 7개에서 tests 46 / failures 0 / errors 0 / skipped 0.
- 전체 검증: `./gradlew --rerun-tasks --no-daemon test --console=plain` 성공. 최종 `build/test-results/test/` XML 132개에서 tests 654 / failures 0 / errors 0 / skipped 0 (652 이상).
- 최종 XML 및 HTML system output에서 `scheduler.*RedisConnectionFailureException`, `RedisConnectionFailureException.*scheduler`, `subscription unavailable`를 검색한 결과는 0건이다. 별도 Redis failure 계약 및 integration fixture의 의도된 RedisConnectionFailureException 출력은 이 scheduler/subscription 조건에 해당하지 않는다.
- `git diff --check`: 통과.
