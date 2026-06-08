# 코스 따라가기 기능 리뷰

## 비개발자용 요약

코스 따라가기는 사용자가 저장된 코스를 선택하고, 현재 위치가 코스 위에 있는지, 출발 가능한지, 경로를 벗어났는지, 완주 조건을 만족했는지 판단하는 기능이다.

이번 성능 개선에서 가장 크게 빨라진 부분이 이 기능이다.

- 코스 경로점 조회 p95: `3003.64ms -> 431.05ms`
- EC2 course-follow p95: `19823.79ms -> 2938.27ms`
- EC2 course-follow p99: `33603.90ms -> 4369.44ms`

쉽게 말하면, "코스를 따라갈 때 서버가 경로 데이터를 읽고 판단하는 시간이 크게 줄었다."

## 현재 API

- 코스 상세: `GET /api/v1/courses/{courseId}`
- 경로점 조회: `GET /api/v1/courses/{courseId}/route-points`
- 코스 다운로드: `GET /api/v1/courses/{courseId}/download`
- 주행 정책 판단: `POST /api/v1/courses/{courseId}/ride-policy/evaluate`

관련 코드:

- `src/main/java/com/bikeprojectminji/bikeback/course/service/CourseService.java`
- `src/main/java/com/bikeprojectminji/bikeback/course/service/CourseRouteSnapshotService.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/policy/service/RidePolicyService.java`
- `src/main/java/com/bikeprojectminji/bikeback/ride/policy/service/RouteProjectionIndex.java`
- `src/main/java/com/bikeprojectminji/bikeback/course/repository/CourseRoutePointRepository.java`

## 이번에 개선한 방식

### 1. 경로점 snapshot cache

기존에는 route-points cache miss 시 여러 요청이 한 줄로 대기했다. 특히 서로 다른 코스를 읽는 요청까지 같은 lock에 묶이면 100명 부하에서 느린 요청이 길게 늘어진다.

개선 후에는 `courseId`별로 cache miss 계산을 분리했다.

- 같은 코스의 첫 조회는 한 번만 DB에서 읽는다.
- 다른 코스 조회는 서로 기다리지 않는다.
- route-points 응답용 DTO와 ride-policy 계산용 `RouteProjectionIndex`를 한 snapshot에 같이 담는다.

왜 이렇게 했나:

- Redis 같은 외부 캐시를 붙이면 운영 복잡도가 커진다.
- 현재 병목은 "전역 lock"이었기 때문에, 단일 JVM in-memory cache를 개선하는 것이 가장 작고 빠른 해결책이었다.

### 2. 주행 정책 계산용 index 재사용

`RidePolicyService`는 현재 위치를 코스 선분에 투영해서 다음을 판단한다.

- 출발점 50m 이내인지
- ACTIVE 상태에서 경로 이탈인지
- 완주율이 80% 이상인지
- 루프 코스인지, 도착형 코스인지

이 계산은 코스 경로점 전체를 여러 번 훑을 수 있다. 그래서 `RouteProjectionIndex`를 snapshot으로 만들어 재사용한다.

## 현재 알고리즘 구조

### 출발 전 PRE_START

- 현재 위치와 코스 시작점 거리를 계산한다.
- 50m 이내면 시작 가능.
- 그 이상이면 시작 불가.

### 주행 중 ACTIVE

- 위치 정확도가 낮거나 오래된 위치면 판단 보류.
- trace를 시간순으로 정렬한다.
- 각 위치를 경로 선분에 투영한다.
- 경로에서 50m 이상 벗어나면 candidate.
- 15초 이상 지속되면 warning.
- 30m 이내로 돌아오면 recovered.
- 전체 경로 중 지나간 비율이 80% 이상이고, 도착점 또는 루프 시작점 조건을 만족하면 완주 가능.

## 추가 개선 방향

1. `RidePolicyService` 책임 분리
   - 현재 파일은 순수 LOC 기준 250줄을 넘는다.
   - `PreStartPolicy`, `OffRoutePolicy`, `CompletionPolicy`, `ProgressCalculator`로 나누면 테스트와 수정이 쉬워진다.

2. 경로 index 계산 최적화
   - 현재 `RouteProjectionIndex`는 preferred segment 주변 window를 먼저 보고, 필요하면 전체 segment를 훑는다.
   - 코스 경로점이 수천 개로 커지면 R-tree, grid index, geohash bucket 같은 공간 index를 검토할 수 있다.

3. 다중 서버 cache 전략
   - 현재 cache는 한 서버 JVM 안에서만 유효하다.
   - 서버가 여러 대로 늘면 Redis cache 또는 DB materialized snapshot 전략이 필요하다.

4. route-points 응답 압축
   - 경로점이 많아지면 응답 크기 자체가 병목이 된다.
   - polyline encoding, zoom-level simplification, paging 또는 chunk download를 검토할 수 있다.

5. 권한과 공개 정책 명확화
   - ride-policy evaluate는 현재 public permit이다.
   - private/unlisted 접근은 service에서 shareToken/owner 정책으로 막지만, 운영 문서에는 public endpoint라는 점을 명확히 적어야 한다.

## 남은 리스크

- in-memory cache는 서버 재시작 시 비워진다.
- route point가 수정되면 evict가 필요하다. 현재 create/update에서는 evict가 들어가 있지만, future bulk job이 생기면 같이 챙겨야 한다.
- `CourseService`와 `RidePolicyService`가 큰 파일이라 기능 추가가 계속되면 구조가 복잡해질 수 있다.
