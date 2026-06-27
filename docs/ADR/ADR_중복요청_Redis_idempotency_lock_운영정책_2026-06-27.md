# ADR: 중복 요청 Redis idempotency lock 운영 정책

- 날짜: 2026-06-27
- 상태: accepted
- 범위: 자유주행 저장, 기록 기반 코스 생성, Redis 장애 fallback, AWS ALB 다중 인스턴스 검증
- 관련 evidence:
  - `ops/smoke/results/concurrency_consistency_20260627_192607.json`
  - `ops/smoke/results/concurrency_consistency_strict_20260627_193509.json`
  - `ops/smoke/results/concurrency_consistency_numeric_subject_lock_20260627_202815.json`
  - `DOCS/검토/동시성_정합성_검증_보고_2026-06-27.md`

## 배경

운영 1차 검증 전에 낮은 부하 동시성 smoke를 실행했다. 사용자 결정으로 아래 API 계약을 적용했다.

- 같은 `ownerUserId + clientRideId` 자유주행 저장은 기존 `rideRecordId`를 `200`으로 반환한다.
- 같은 `ownerUserId + sourceRideRecordId` 기록 기반 코스 생성은 기존 `courseId`를 `200`으로 반환한다.
- Party WebSocket socket token은 브라우저/모바일 WebSocket에서 쓰기 쉬운 `?socketToken=` query parameter로 전달한다.

코드 수정 후 자유주행 저장과 WebSocket은 승인 정책을 만족했다. 그러나 기록 기반 코스 생성은 DB 데이터 정합성은 지켰지만 사용자/API 계약을 완전히 만족하지 못했다.

strict smoke 결과:

- `duplicateClientRideId`: `200` 10건, DB ride record 1건, finalization job 1건
- `duplicateCourseCreate`: `200` 4건, `503` 6건, DB course 1건
- `socketTokenReuse`: 첫 연결 `101`, 재사용 연결 policy close frame

## 문제

기록 기반 코스 생성 10개가 같은 `sourceRideRecordId`로 동시에 들어오면 DB unique constraint가 중복 row는 막는다. 하지만 동시에 insert를 시도한 요청들이 unique conflict 대기와 후속 조회 과정에서 DB connection을 오래 점유한다. 그 결과 Hikari pool이 꽉 차고 일부 요청은 `503 database_unavailable`로 떨어졌다.

운영 관점에서 이 상태는 불완전하다.

- 데이터는 안전하지만 사용자는 같은 코스 생성 재시도에서 성공 화면 대신 503을 볼 수 있다.
- `503`은 운영 장애 지표를 오염시킨다.
- AWS ALB 2 targets에서는 같은 사용자의 중복 요청이 다른 인스턴스로 갈 수 있어 단일 JVM 내부 제어만으로는 부족하다.
- Hikari pool size를 키우면 증상을 늦출 수는 있지만, 같은 key 중복 요청이 DB connection을 점유하는 구조 자체는 유지된다.

## 목표

- 같은 idempotency key의 중복 요청은 가능한 한 DB insert 경쟁 전에 직렬화한다.
- 승인 정책처럼 중복 요청도 기존 리소스를 `200`으로 반환한다.
- ALB 2 targets에서도 같은 정책이 유지되어야 한다.
- Redis 장애가 있어도 데이터 정합성은 DB unique constraint로 최종 보호한다.
- Redis lock은 영구 lock이 되지 않도록 TTL을 둔다.
- AWS 테스트에서는 Redis 정상, Redis 실패, DB pressure 상황을 각각 evidence로 남긴다.

## 선택지

### A. 현재 방식 유지

DB unique constraint를 최종 방어선으로 두고, 충돌 후 기존 row를 조회해 `200`을 반환한다.

장점:

- 구현이 가장 단순하다.
- Redis나 별도 lock 정책이 없어도 DB 정합성은 유지된다.
- Redis 장애와 무관하다.

단점:

- 이미 strict smoke에서 `503`이 확인됐다.
- 같은 key의 중복 요청이 DB connection을 점유한다.
- ALB 다중 인스턴스에서 중복 요청이 늘면 Hikari pressure가 더 빨리 나타날 수 있다.

판단:

- 운영 1차 E2E/AWS 검증 기준으로는 부족하다.

### B. 인스턴스 내부 keyed lock

애플리케이션 메모리에서 `ownerUserId + clientRideId`, `ownerUserId + sourceRideRecordId` 단위로 lock을 잡는다.

장점:

- 구현이 쉽고 빠르다.
- 단일 인스턴스에서는 DB insert 경쟁을 크게 줄인다.
- Redis 의존성이 없다.

단점:

- ALB 2 targets에서는 인스턴스 간 lock이 공유되지 않는다.
- 장애 검증에서 “다중 인스턴스 상태 공유” 기준을 만족하지 못한다.
- 인스턴스 재시작 시 lock 상태가 사라진다.

판단:

- 단일 인스턴스 임시 완화책으로는 가능하지만, 이번 목표의 ALB 2 targets 검증에는 부족하다.

### C. Redis 기반 idempotency lock

Redis에 짧은 TTL을 가진 lock key를 만든다. lock key 예시는 다음과 같다.

- `bike:idempotency-lock:ride-record:{ownerUserId}:{clientRideId}`
- `bike:idempotency-lock:course-from-ride:{ownerUserId}:{sourceRideRecordId}`

기본 흐름:

1. 요청 validation과 사용자 식별을 먼저 수행한다.
2. 기존 리소스가 있으면 즉시 `200`으로 반환한다.
3. 기존 리소스가 없으면 Redis lock 획득을 시도한다.
4. lock을 얻은 요청만 생성 트랜잭션을 수행한다.
5. lock을 얻지 못한 요청은 짧게 대기하며 기존 리소스 조회를 반복한다.
6. 제한 시간 안에 기존 리소스가 보이면 `200`으로 반환한다.
7. 제한 시간 안에 보이지 않으면 `503` 또는 `409/202` 중 정책에 맞는 응답을 반환한다.
8. Redis 장애 시에는 lock 없이 현재 DB unique fallback을 사용한다.

장점:

- ALB 2 targets에서도 lock이 공유된다.
- 같은 key 중복 요청이 DB insert 경쟁으로 몰리는 것을 줄인다.
- Redis 장애가 있어도 DB unique constraint가 최종 정합성을 지킨다.
- AWS 상태 공유 검증 항목과 잘 맞는다.

단점:

- Redis lock TTL, 대기 시간, 실패 응답 계약을 정해야 한다.
- Redis 장애 시에는 현재 방식 fallback이라 DB pressure가 다시 생길 수 있다.
- lock 구현과 테스트가 추가된다.

판단:

- 운영 1차 E2E/AWS 검증 기준으로 추천한다.

### D. PostgreSQL advisory lock

PostgreSQL advisory lock을 `ownerUserId + sourceRideRecordId` 기준으로 잡는다.

장점:

- 다중 인스턴스에서도 DB 기준으로 공유된다.
- Redis 없이 구현할 수 있다.

단점:

- lock 대기 자체가 DB connection을 점유할 수 있다.
- 이번에 관측된 Hikari pressure를 줄이는 목적에는 Redis보다 약하다.
- DB vendor 의존성이 강해진다.

판단:

- Redis를 쓸 수 없을 때의 대안으로만 둔다.

## 결정

C. Redis 기반 idempotency lock을 선택한다.

정책:

- lock TTL: 10초
- lock 대기 시간: 최대 2초
- 대기 중 조회 주기: 50~100ms jitter
- lock 획득 실패 후 기존 리소스가 조회되면 `200 existing`
- lock 획득 실패 후 제한 시간 내 기존 리소스가 조회되지 않으면 `503` backpressure
- Redis 장애 시 lock 없이 DB unique fallback을 사용하고 provider/infra failure metric을 남긴다.
- lock release는 owner token을 비교해 본인이 잡은 lock만 삭제한다.

## 구현 범위

1차 구현 대상:

- 자유주행 저장 `ownerUserId + clientRideId`
- 기록 기반 코스 생성 `ownerUserId + sourceRideRecordId`
- Redis lock adapter
- lock 획득/대기/실패 metric
- 단위 테스트
- local strict concurrency smoke

비범위:

- 전체 API 공통 idempotency key 도입
- Kafka/SQS/DLQ 도입
- DB schema 변경
- Redis route snapshot cache 구현
- Hikari pool size 증설로 문제를 덮는 방식

## 검증 기준

Local:

- `RideRecordServiceTest`
- `CourseServiceTest`
- `RidePartyLocationWebSocketHandlerTest`
- `python3 -m py_compile ops/smoke/concurrency_consistency.py`
- `ops/smoke/concurrency_consistency.py`
  - `duplicateClientRideId=true`
  - `duplicateCourseCreate=true`
  - `socketTokenReuse=true`
  - 중복 주행 저장 statusCounts: `{"200": 10}`
  - 중복 코스 생성 statusCounts: `{"200": 10}`

AWS:

- single instance에서 같은 smoke runner 통과
- ALB single target에서 request id/header/WebSocket 확인
- ALB 2 targets에서 같은 key 중복 요청이 모두 기존 리소스 `200`으로 수렴하는지 확인
- Redis 장애 drill에서는 DB unique fallback이 정합성을 유지하고 실패율/503이 evidence에 기록되는지 확인

## 결정 결과

사용자 결정으로 아래 정책을 확정했다.

1. Redis idempotency lock을 자유주행 저장과 기록 기반 코스 생성 둘 다 적용한다.
2. lock 대기 시간 2초, TTL 10초를 1차 기본값으로 둔다.
3. lock 획득 실패 후 기존 리소스를 못 찾으면 `503 backpressure`로 응답한다.

## 2026-06-27 구현 중 보정

Local strict smoke에서 Redis lock을 단순 적용했을 때도 기록 기반 코스 생성이 실패했다. 원인은 lock 경쟁 요청이 DB 조회를 먼저 수행한 뒤 대기하면서 Hikari connection을 점유한 것이다.

보정 결정:

- `course_from_ride`는 DB 조회 전에 numeric JWT subject에서 `ownerUserId`를 파싱해 Redis lock key를 먼저 만든다.
- legacy non-numeric subject는 기존 `AuthService.findUserBySubject`로 fallback한다.
- `course_from_ride`는 command 성격이므로 기존 리소스 조회도 lock 경쟁자 대기 경로에서만 수행한다.
- `course_from_ride` 대기 정책은 생성 작업 시간을 고려해 최대 8초, 조회 주기 300~500ms jitter로 둔다.
- `ride_record_regenerate`도 command 성격이므로 lock을 얻은 요청 하나만 재처리 mark를 수행하고, 경쟁 요청은 현재 finalization 상태를 `200`으로 반환한다.

최종 local evidence:

- Evidence: `ops/smoke/results/concurrency_consistency_numeric_subject_lock_20260627_202815.json`
- `duplicateClientRideId`: `200` 10건, ride record 1건, finalization job 1건
- `duplicateRegenerate`: `200` 10건, finalization job 1건
- `duplicateCourseCreate`: `200` 10건, course 1건, route point 3건
- Prometheus:
  - `bike_idempotency_lock_total{operation="course_from_ride",outcome="existing_after_wait"} 9`
  - `hikaricp_connections_timeout_total 0`
  - `hikaricp_connections_pending 0`
