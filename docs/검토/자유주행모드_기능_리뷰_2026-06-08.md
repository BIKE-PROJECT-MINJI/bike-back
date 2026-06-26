# 자유주행모드 기능 리뷰

## 비개발자용 요약

자유주행모드는 사용자가 앱에서 자유롭게 주행한 GPS 기록을 서버에 저장하는 기능이다. 저장된 원본 GPS는 그대로 두고, 서버가 한 번 더 정리한 최종 경로를 만든다. 이 최종 경로는 나중에 코스로 저장할 때 사용된다.

이번 개선에서 자유주행모드는 "속도 개선"보다 "데이터가 꼬이지 않게 하는 안정성 개선"이 핵심이었다.

기존 문제:

- 저장 직후 후처리와 재처리가 동시에 들어오면 같은 processed point를 중복 insert할 수 있었다.
- 이때 `duplicate key`, `UnexpectedRollbackException`이 발생했다.

개선 결과:

- before error scan에서는 중복키/rollback 오류가 있었다.
- after error scan은 비어 있었다.
- course/free 100명 부하에서 HTTP failure 0%로 통과했다.

## 현재 API

- 주행 기록 저장: `POST /api/v1/ride-records`
- 주행 기록 목록: `GET /api/v1/ride-records`
- 후처리 상태 조회: `GET /api/v1/ride-records/{rideRecordId}`
- 후처리 재요청: `POST /api/v1/ride-records/{rideRecordId}/regenerate`

관련 코드:

- `src/main/java/com/bikeprojectminji/bikeback/ride/service/RideRecordService.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/service/RideRecordFinalizationService.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/service/RideRecordFinalizationProcessor.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/service/RideRecordFinalizationWriter.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/service/RideRecordFinalizationFailureService.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/repository/RideRecordRepository.java`

## 현재 저장 흐름

1. 요청 검증
   - 시작/종료 시각
   - 거리/시간
   - GPS 포인트
   - pointOrder 중복 여부
   - 좌표 범위

2. 사용자 확인
   - JWT subject로 현재 사용자 조회

3. 중복 저장 방지
   - `clientRideId`가 있으면 같은 사용자/같은 clientRideId 기록을 다시 만들지 않는다.

4. raw point 저장
   - `ride_records`
   - `ride_record_points`

5. commit 이후 async finalization 요청
   - 원본 저장 트랜잭션이 성공한 뒤에만 후처리를 시작한다.

6. processed point 생성
   - raw point를 canonicalize한다.
   - 기존 processed point 삭제 후 flush한다.
   - 새 processed point를 저장한다.
   - 상태를 READY로 바꾼다.

## 이번에 개선한 방식

### 1. row lock으로 같은 주행 기록 동시 처리 방지

`RideRecordRepository.findByIdForUpdate`가 `PESSIMISTIC_WRITE` lock을 건다.

쉽게 말하면, 같은 rideRecordId를 두 작업자가 동시에 수정하려고 하면 한 명씩 줄 세운다.

왜 이렇게 했나:

- processed point는 `(ride_record_id, point_order)`가 unique다.
- 두 작업자가 동시에 insert하면 같은 번호를 중복 저장하려고 한다.
- DB row lock으로 상태 전이와 processed point 교체 순서를 고정해야 한다.

### 2. READY 상태면 skip

이미 READY인 기록은 finalization worker가 다시 처리하지 않는다.

왜 이렇게 했나:

- 이미 최종 경로가 준비된 기록을 다시 건드리면 불필요한 delete/insert가 생긴다.
- 부하 상황에서는 이 중복 작업이 DB lock과 pool을 더 압박한다.

### 3. 실패 처리 트랜잭션 분리

processed point 교체 중 실패하면 교체 트랜잭션은 rollback된다. 실패 상태 기록은 별도 `REQUIRES_NEW` 트랜잭션에서 저장한다.

왜 이렇게 했나:

- 실패했는데 기존 processed point까지 지워지면 사용자가 이전 정상 경로를 잃는다.
- 실패 상태는 남겨야 운영자가 원인을 볼 수 있다.

## 추가 개선 방향

1. finalization job queue 도입
   - 현재는 Spring `@Async`로 바로 실행한다.
   - 작업량이 커지면 DB 기반 queue, Redis queue, 또는 message queue로 전환할 수 있다.

2. processed point upsert 전략 검토
   - 지금은 delete + flush + insert다.
   - PostgreSQL `ON CONFLICT` 기반 upsert 또는 temp table 교체 방식이 더 빠를 수 있다.

3. canonicalizer 고도화
   - 현재는 raw GPS를 정리하는 단계다.
   - 향후 GraphHopper map matching 또는 snapping을 붙이면 실제 도로에 더 가까운 경로가 된다.

4. 상태 전이 enum 저장 개선
   - 현재 entity 내부 필드는 String이고 getter에서 enum으로 변환한다.
   - JPA `@Enumerated(EnumType.STRING)`으로 명확히 바꿀 수 있다.

5. 대량 삭제 성능
   - 회원 탈퇴나 대량 정리 시 point 삭제가 커질 수 있다.
   - FK cascade와 bulk delete 전략을 더 명확히 정리하면 좋다.

## 남은 리스크

- `@Async`는 process 내부 실행이라 서버 재시작 중인 작업 복구가 어렵다.
- finalization 실패가 반복될 때 retry/backoff 정책이 아직 명확하지 않다.
- processed point 생성은 DB 쓰기량이 많다. 포인트 수가 커지면 batch size와 DB pool 설정이 중요해진다.
