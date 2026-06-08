# Course-follow 성능 개선 보고서

## 요약

이번 작업은 코스 따라가기 화면에서 많이 호출되는 백엔드 경로를 개선한 작업이다. 특히 코스 생성 후 상세/경로점/주행정책을 읽는 흐름에서 p95/p99가 높고, 주행 기록 finalization 중복 처리로 DB 중복키 오류가 발생하던 문제를 줄였다.

핵심 결과:

- 로컬 최종 course/free 100VU after: k6 exit code `0`, HTTP failure `0%`, course-follow p95 `457.55ms`, p99 `751.53ms`
- 로컬 재측정 before/after: route-points p95 `3003.64ms -> 431.05ms`, 85.6% 단축
- EC2 r8 course-follow p95: 19823.79ms -> 2938.27ms, 85.2% 단축
- EC2 r8 course-follow p99: 33603.90ms -> 4369.44ms, 87.0% 단축
- EC2 r8 route-points p95: 36270.04ms -> 3691.95ms, 89.8% 단축
- EC2 r8 처리량: 52.87 req/s -> 63.35 req/s, 19.8% 증가
- 중복키/rollback 오류: before에서 발생, after error scan에서는 미발생
- AWS 테스트 인스턴스, 보안그룹, 키 페어는 테스트 후 삭제 완료

용어:

- p95는 100개 요청 중 느린 쪽 5개를 제외했을 때의 응답 시간이다. 일반 사용자가 체감하는 느린 요청을 보는 지표다.
- p99는 100개 요청 중 거의 가장 느린 요청 쪽을 보는 지표다. 장애 직전의 꼬리 지연을 확인할 때 쓴다.
- VU는 k6의 가상 사용자 수다. 100VU는 동시에 100명이 비슷한 흐름을 실행하는 조건이다.
- req/s는 초당 처리한 HTTP 요청 수다.

## 무엇을 바꿨나

1. 코스 경로점 cache miss 병목 제거

   기존에는 route-points cache miss가 하나의 전역 lock으로 묶였다. 서로 다른 코스 요청도 한 줄로 기다리게 되어 100명 부하에서 tail latency가 커졌다. courseId별 계산으로 바꿔 서로 다른 코스는 병렬로 처리되게 했다.

2. 주행 기록 finalization 동시성 보강

   저장 직후 regenerate나 코스 생성이 같이 들어오면 async finalization이 같은 processed point를 중복 insert할 수 있었다. `PESSIMISTIC_WRITE` lock, READY 상태 skip, delete 후 flush를 적용해 처리 순서를 고정했다. 실패 시에는 경로 교체 트랜잭션을 rollback하고, 실패 상태만 별도 `REQUIRES_NEW` 트랜잭션으로 기록해 기존 processed point를 보존한다.

3. 코스 생성 응답 경로에서 부가 작업 분리

   업적 부여는 사용자 응답에 꼭 필요한 코스 생성 본 작업이 아니다. transaction commit 이후 bounded async executor에서 별도 트랜잭션으로 실행되도록 분리했다.

4. async/DB pool 상한 명시

   Spring 기본 async executor는 부하 상황에서 스레드를 과도하게 늘릴 수 있다. `bike.async.*` 설정으로 bounded executor를 만들고, 테스트 compose에서는 Hikari pool을 30으로 명시해 100VU 테스트 조건을 재현 가능하게 했다.

5. 부하 테스트 계측 개선

   k6에 endpoint별 duration metric, course-follow READY 대기 metric, AWS EC2 자동 생성/삭제 스크립트를 추가했다. before/after를 같은 조건에서 비교할 수 있게 했다.

## 로컬 테스트 결과

조건:

- Docker compose test stack
- 100 VU, 60초
- 포트: API 8080, management health 8081
- 테스트 후 `docker compose down -v`, `.env.test` 삭제, 8080/8081/18081 포트 비움

로컬은 두 종류의 증거를 남겼다.

1. before/after 재측정 receipt
   - before k6 exit code: `99`
   - after k6 exit code: `0`
   - before error scan: processed point 중복키/rollback 오류 재현
   - after error scan: 비어 있음

| 항목 | Before | After | 개선 |
| --- | ---: | ---: | ---: |
| 전체 p95 | 2238.81ms | 982.80ms | 56.1% 단축 |
| 전체 p99 | 10714.75ms | 10042.13ms | 6.3% 단축 |
| course-follow p95 | 1334.56ms | 559.02ms | 58.1% 단축 |
| course-follow p99 | 2782.91ms | 1098.06ms | 60.5% 단축 |
| route-points p95 | 3003.64ms | 431.05ms | 85.6% 단축 |
| route-points p99 | 4482.02ms | 662.84ms | 85.2% 단축 |
| HTTP req/s | 100.78 | 128.16 | 27.2% 증가 |

2. 최종 코드 course/free 100VU after sanity gate

| 항목 | After |
| --- | ---: |
| k6 exit code | 0 |
| checks | 100% |
| HTTP failure rate | 0% |
| 전체 p95 / p99 | 749.88ms / 1334.27ms |
| course-follow p95 / p99 | 457.55ms / 751.53ms |
| free-ride p95 / p99 | 562.76ms / 1071.84ms |
| route-points p95 / p99 | 385.31ms / 665.45ms |
| HTTP req/s | 226.18 |

원본 증거는 `.omo/ulw-loop/evidence/course-follow-perf-20260608`에 있고, PR에서 바로 확인할 수 있는 compact evidence는 `ops/loadtest/results/course-follow-perf-20260608`에 둔다.

## EC2 100명 부하 테스트 결과

조건:

- AWS ap-northeast-2 임시 EC2 `t3.xlarge`
- Docker compose test stack
- 100 VU, 2분
- 테스트 후 EC2 instance, security group, key pair 삭제
- r8 측정은 이번 개선 범위만 검증하기 위해 AI route를 제외하고 free-ride 50 VU, course-follow 50 VU로 구성했다.
- r8 before는 course-follow threshold 초과로 k6 exit code `99`, r8 after는 k6 exit code `0`이다.

| 항목 | Before | After | 개선 |
| --- | ---: | ---: | ---: |
| 전체 p95 | 4008.11ms | 3188.56ms | 20.4% 단축 |
| 전체 p99 | 22793.78ms | 5024.96ms | 78.0% 단축 |
| course-follow p95 | 19823.79ms | 2938.27ms | 85.2% 단축 |
| course-follow p99 | 33603.90ms | 4369.44ms | 87.0% 단축 |
| route-points p95 | 36270.04ms | 3691.95ms | 89.8% 단축 |
| route-points p99 | 44091.46ms | 4607.30ms | 89.6% 단축 |
| HTTP req/s | 52.87 | 63.35 | 19.8% 증가 |
| iteration/s | 14.41 | 14.14 | 1.9% 감소 |
| HTTP failure rate | 0% | 0% | 유지 |
| checks | 100% | 100% | 유지 |

해석:

- 이번 개선 범위인 course-follow와 route-points는 뚜렷하게 개선됐다.
- after는 100명 course/free 시나리오에서 k6 threshold를 통과했다.
- iteration/s는 거의 유지됐지만, 병목이던 course-follow tail latency가 크게 내려가 사용자가 체감하는 대기 시간이 줄었다.
- AI route까지 포함한 r5 측정은 별도 참고값이다. r5에서는 AI route check가 모두 실패했기 때문에 전체 k6 통과 증거로 쓰지 않고, AI route 후속 리스크로 분리한다.

## 안정성 및 데이터 정합성

Before:

- `duplicate key value violates unique constraint "uq_ride_record_processed_points_record_order"`
- `UnexpectedRollbackException`
- `ride_record_finalization_failed`

After:

- 로컬 최종 course/free after error scan 비어 있음
- EC2 r8 after error scan 비어 있음
- course-follow/free-ride check 실패 없음

## 자원 효율성

EC2 r8 docker stats:

- backend: 712.6MiB -> 561.3MiB
- postgres: 84.69MiB -> 83.82MiB
- graphhopper: CPU 247.54% -> 112.64%, memory 2.235GiB -> 2.236GiB
- ai-route-worker: 76.3MiB -> 76.09MiB

GraphHopper CPU가 가장 큰 병목으로 남아 있다. 이번 작업은 course-follow hot path 개선이므로 GraphHopper/AI route capacity 튜닝은 후속 작업으로 분리한다.

## 검증

- `./gradlew --no-daemon test --console=plain`: 성공
- targeted Gradle tests: 성공
- `bash -n ops/loadtest/run-aws-compose-k6.sh`: 성공
- `node --check ops/loadtest/k6/ai-route-graphhopper-100-users.js`: 성공
- `k6 inspect` course/free scenario: 성공
- `git diff --check`: 성공
- 로컬 compose/k6 before/after: 완료
- 로컬 최종 course/free 100VU after: k6 exit code `0`, checks 100%, HTTP failure 0%
- AWS EC2 compose/k6 before/after: 완료
- AWS cleanup: 완료
- 로컬 8081 management health smoke: `401` 응답으로 포트 바인딩 확인
- 로컬 Gemini/GraphHopper key 주입 smoke: course-follow/free-ride check는 통과, AI route check는 실패
- AWS EC2 r8 course/free 100 VU: after k6 exit code `0`, checks 100%, HTTP failure 0%
- AWS cleanup verification: instance `terminated`, security group/key pair describe 결과 NotFound 계열 확인

## 남은 리스크

- AI route flow는 실제 Gemini key를 주입한 로컬 smoke에서도 `from-text` endpoint가 200을 반환하지 못했다. 이번 PR은 course-follow hot path 성능 개선으로 범위를 고정하고, AI route/GraphHopper readiness 문제는 후속 이슈로 분리해야 한다.
- AI route까지 포함한 로컬 100VU 혼합 시나리오는 최종 코드에서도 DB pool을 압박할 수 있다. 이번 PR에서는 bounded async executor와 compose Hikari pool 설정으로 course/free 범위를 통과시켰고, AI route 포함 capacity는 후속으로 별도 튜닝해야 한다.
- GraphHopper CPU 사용량이 높아 AI route p95/p99가 전체 p99를 끌어올린다.
- in-memory cache는 단일 backend instance 기준이다. 다중 인스턴스 배포에서는 공유 cache 전략을 다시 정해야 한다.
