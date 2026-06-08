# 백엔드 아키텍처, DB, WebSocket 리뷰

## 비개발자용 요약

현재 백엔드는 기능별 책임이 비교적 잘 나뉘어 있다.

- Controller: HTTP/WebSocket 요청을 받는다.
- Service: 실제 업무 흐름을 처리한다.
- Repository: DB 조회/저장을 담당한다.
- Entity: DB에 저장되는 데이터 구조다.
- 외부 연동: GraphHopper, AI worker, weather, Redis 같은 외부 시스템은 별도 client/store로 분리되어 있다.

이번 리뷰에서 큰 방향은 좋다고 판단했다. 다만 다음 개선 여지가 있다.

- 큰 Service 파일을 더 작은 책임으로 나누는 것이 좋다.
- 일부 쿼리는 데이터가 많아지면 N+1처럼 느려질 수 있다.
- WebSocket origin 제한을 운영 도메인 기준으로 좁히는 것이 좋다.
- in-memory cache는 서버 여러 대 운영 시 한계가 있다.
- async finalization은 장애 복구까지 고려하면 queue 기반으로 발전시키는 것이 좋다.

## WebSocket은 어디서 쓰나

현재 WebSocket은 AI 코스 생성에서만 사용한다.

- 설정: `src/main/java/com/bikeprojectminji/bikeback/airoute/websocket/AiRouteWebSocketConfig.java`
- 핸들러: `src/main/java/com/bikeprojectminji/bikeback/airoute/websocket/AiRouteWebSocketHandler.java`
- 경로: `/ws/v1/ai-routes`
- 테스트: `src/test/java/com/bikeprojectminji/bikeback/airoute/contract/AiRouteContractSmokeTest.java`

동작 방식:

1. 클라이언트가 WebSocket 연결을 연다.
2. 서버가 `accepted` 메시지를 보낸다.
3. 클라이언트가 `AiRoutePlanRequest` JSON을 보낸다.
4. 서버가 quota를 확인한다.
5. `AiRoutePlannerService.plan()`을 호출한다.
6. 성공하면 `{ "type": "plan", "data": ... }`를 보낸다.
7. 실패하면 `{ "type": "error", ... }`를 보낸다.
8. 연결을 닫는다.

주의:

- 이 WebSocket은 계속 스트리밍하는 구조가 아니라 "한 번 요청하고 한 번 응답한 뒤 닫는 구조"다.
- `SecurityConfig`에서 `/ws/v1/ai-routes/**`는 authenticated로 되어 있다.
- `AiRouteWebSocketConfig`의 allowed origin은 `*`다. 운영에서는 프론트 도메인으로 제한하는 것이 좋다.

## N+1 문제 리뷰

전형적인 JPA N+1은 "부모 목록 조회 후 각 부모의 lazy child를 하나씩 조회"할 때 생긴다. 현재 코드는 대부분 JPA 연관관계를 쓰지 않고 `courseId`, `rideRecordId`, `ownerUserId` 같은 id 필드로 직접 조회한다. 그래서 전형적인 lazy loading N+1 위험은 낮다.

다만 아래는 데이터가 커지면 N+1과 비슷한 성능 문제가 될 수 있다.

### 1. featured course 거리 조회

위치 기반 추천 코스 조회에서 native query로 course id를 찾은 뒤 `entityManager.find()`를 반복한다.

관련 코드:

- `src/main/java/com/bikeprojectminji/bikeback/course/repository/CourseRepositoryImpl.java`

현재 limit은 3이라 위험은 낮다. 하지만 추천 개수를 늘리면 `N개 id 조회 + N번 entity 조회`가 된다.

개선 방향:

- native query에서 필요한 목록 응답 필드를 바로 projection으로 가져온다.
- 또는 `where id in (...)` 한 번으로 course를 묶어 조회한다.

### 2. 업적 지급 exists 반복

코스 완료 후 업적 후보별로 `exists` 확인 후 저장한다.

관련 코드:

- `src/main/java/com/bikeprojectminji/bikeback/achievement/service/AchievementService.java`

현재 후보가 최대 3개 정도라 문제는 작다. 하지만 업적 종류가 많아지면 반복 쿼리가 늘어난다.

개선 방향:

- 현재 사용자/업적 타입의 기존 grant를 한 번에 조회한다.
- PostgreSQL `ON CONFLICT DO NOTHING` 방식으로 중복을 DB에 맡긴다.

### 3. profile/activity summary 집계

`RideRecordRepository`에는 count/sum 쿼리가 여러 개 있다. 각 API에서 여러 집계를 따로 호출하면 쿼리 수가 늘 수 있다.

개선 방향:

- activity summary 전용 projection query로 count/sum을 한 번에 묶는다.
- weekly/overall이 자주 호출되면 materialized summary table을 검토한다.

## 락과 트랜잭션 리뷰

### 잘 된 부분

1. 자유주행 finalization
   - `findByIdForUpdate`로 같은 rideRecordId의 동시 처리를 막는다.
   - READY 상태는 skip한다.
   - 실패 상태 기록은 별도 `REQUIRES_NEW`로 남긴다.

2. 코스 생성 후 업적 부여
   - 코스 저장 트랜잭션 commit 이후 async로 실행한다.
   - 사용자 응답 경로에서 부가 작업을 분리했다.

3. 코스 route point 교체
   - route point 변경 후 cache evict와 route geometry refresh를 같이 수행한다.

### 추가 확인이 필요한 부분

1. `resolveNextDisplayOrder`
   - 새 코스 생성 시 현재 최대 displayOrder를 읽고 +1 한다.
   - 동시에 여러 사용자가 코스를 만들면 같은 displayOrder가 생길 수 있다.
   - 현재 displayOrder가 unique가 아니라면 기능상 큰 문제는 아닐 수 있지만, 정렬 안정성이 중요하면 sequence 또는 createdAt/id 정렬로 단순화하는 편이 좋다.

2. course route point update
   - `deleteByCourseId` 후 `saveAll` 구조다.
   - 같은 courseId를 동시에 수정하면 마지막 writer가 이긴다.
   - 운영에서 동시 수정 가능성이 있으면 course row lock 또는 optimistic version column이 필요하다.

3. WebSocket quota
   - REST와 WebSocket 모두 quota를 확인한다.
   - 다만 WebSocket subject가 없으면 빈 문자열로 quota 확인이 들어갈 수 있다. Security가 인증을 막는 것이 전제다.

## DB 설계 리뷰

좋은 점:

- `course_route_points(course_id, point_order)` 인덱스가 있다.
- `ride_record_points(ride_record_id, point_order)` 인덱스가 있다.
- processed point에는 unique index가 있어 중복을 DB가 막는다.
- `client_ride_id` unique index로 앱 재전송 중복 저장을 막는다.
- PostGIS geometry와 GiST index가 있어 위치 기반 추천 코스 확장성이 있다.

개선 후보:

1. 상태 컬럼 enum 명확화
   - `ride_records.finalization_status`는 DB에서는 varchar, entity에서는 String이다.
   - Java entity에서 enum mapping을 쓰면 잘못된 문자열 상태를 더 일찍 막을 수 있다.

2. owner/status/endedAt 복합 인덱스
   - ride list와 activity summary는 owner, status, endedAt 기준 조회가 많다.
   - 현재 owner 기반 인덱스는 있지만 summary 쿼리까지 최적화하려면 `(owner_user_id, finalization_status, ended_at)` 인덱스를 검토할 수 있다.

3. public course listing 인덱스
   - public + report_hidden + display_order/id 정렬 조회가 많다.
   - `(visibility, report_hidden, display_order, id)` 인덱스를 검토할 수 있다.

4. route snapshot cache table
   - route-points 응답이 계속 커지면 DB에서 매번 point rows를 읽는 대신 encoded polyline 또는 JSON snapshot table을 둘 수 있다.

## 큰 파일/책임 분리 리뷰

순수 LOC 기준으로 큰 파일이 있다.

- `CourseService`: 약 445 LOC
- `RidePolicyService`: 약 558 LOC
- `ops/loadtest/run-aws-compose-k6.sh`: 약 394 LOC

이 파일들이 지금 당장 장애를 일으키는 것은 아니다. 하지만 기능이 계속 붙으면 읽기 어렵고 테스트가 커진다.

추천 분리:

- `CourseReadService`: 목록, 상세, 다운로드, 검색
- `CourseWriteService`: 생성, 수정, 공개범위, 공유
- `FeaturedCourseService`: 추천 코스 거리 계산
- `PreStartPolicy`: 출발 가능 판단
- `OffRoutePolicy`: 경로 이탈 판단
- `CompletionPolicy`: 완주 판단
- `ProgressCalculator`: 진행률 계산
- AWS runner는 `package`, `ec2`, `remote-run`, `evidence` 함수군 또는 별도 shell script로 분리

## 향후 우선순위

1. AI route 안정화
   - `from-text` 성공률, GraphHopper warm-up, AI worker timeout, 실패 reason metric부터 잡는다.

2. RidePolicyService 분리
   - 기능은 그대로 두고 정책 계산 클래스를 나눈다.
   - 테스트를 policy별로 나누면 유지보수가 쉬워진다.

3. course route snapshot 다중 서버 전략
   - Redis 또는 DB snapshot cache를 검토한다.

4. DB 인덱스 보강
   - 실제 slow query log 또는 `EXPLAIN ANALYZE`로 owner/status/endedAt, visibility/reportHidden/displayOrder 인덱스 효과를 확인한다.

5. WebSocket 운영 보안
   - allowed origin 제한
   - 인증 실패 테스트 추가
   - 필요하면 WebSocket도 REST와 같은 error code 체계로 정리
