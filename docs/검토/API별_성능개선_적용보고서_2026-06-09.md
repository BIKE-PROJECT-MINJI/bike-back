# API별 성능 개선 검토 및 적용 보고서

## 요약

이번 작업에서는 전체 API를 다시 훑고, 즉시 적용해도 계약 변경 위험이 낮은 성능 개선을 먼저 반영했다.

적용한 개선:

- `GET /api/v1/profile/me/activity-summary`
  - 기존: ride/course 개별 집계 쿼리 8회
  - 변경: activity summary 전용 projection 쿼리 2회
  - 추가: 집계 조건에 맞는 복합 인덱스 2개
- `GET /api/v1/courses/featured`
  - 기존: PostGIS native query 후 추천 코스 수만큼 `entityManager.find()` 반복
  - 변경: native query로 id/distance를 가져온 뒤 `where id in (...)` batch fetch 1회
  - 추가: k6 `home/profile/health` read baseline 측정

이번에 코드로 적용하지 않은 개선 후보는 별도 후속 항목으로 남겼다. 이유는 API 계약 변경, 캐시 무효화 정책, 운영 복잡도, 실제 부하 증거 필요성이 더 크기 때문이다.

## 적용 상세

### `GET /api/v1/profile/me/activity-summary`

문제:

- 주간/전체 요약을 만들기 위해 같은 사용자 조건으로 count/sum을 여러 번 호출했다.
- 주행 기록이 많아질수록 DB 왕복 횟수와 반복 스캔 비용이 커진다.

개선 방식:

- `RideRecordActivityAggregate`로 READY 주행의 전체/주간 집계를 한 번에 조회한다.
- `CourseActivityAggregate`로 응답에 필요한 주간 저장 코스 수만 조회한다.
- `ProfileService`는 aggregate를 DTO로 변환하는 조립 책임만 갖게 했다.
- Flyway `V21`로 다음 인덱스를 추가했다.
  - `ride_records(owner_user_id, finalization_status, ended_at)`
  - `courses(owner_user_id, created_at)`

왜 이렇게 했나:

- API 응답 계약을 바꾸지 않고 성능만 개선할 수 있다.
- Service가 직접 여러 repository 메서드를 조합하는 반복을 줄인다.
- 인덱스가 owner/status 선행 조건을 보조하고, 주간 범위가 필요한 경우 date 조건까지 이어서 활용할 수 있게 한다.

트레이드오프:

- JPQL projection 쿼리가 길어졌다.
- ride/course write 시 인덱스 갱신 비용이 소폭 증가한다.
- 대규모 통계 기능까지 확장되면 summary table이 더 적합할 수 있다.

검증:

- 단위 테스트에서 aggregate 2회 호출 구조를 고정했다.
- 통합 테스트에서 READY 주행만 집계하고, 이번 주 내 코스만 주간 저장 코스로 세는지 확인했다.
- 2026-06-11 추가 검증에서 Hibernate statistics로 서비스 호출 1회당 prepared statement 2개를 확인했다.

## API별 후속 개선 후보

### 코스 목록/추천

대상:

- `GET /api/v1/courses/featured`
- `GET /api/v1/courses`
- `GET /api/v1/courses/search`

후보:

- featured course 위치 정렬에서 native query 결과 id를 다시 `entityManager.find()`로 읽던 구조는 `where id in (...)` batch fetch로 2026-06-11 반영했다.
- public listing에는 `(visibility, report_hidden, display_order, id)` 계열 인덱스를 검토한다.

트레이드오프:

- 현재 featured limit이 작아 체감 개선은 제한적이다.
- projection을 바로 쓰면 목록 응답 필드 변경에 repository가 더 강하게 묶인다.

### 코스 상세/경로점/따라가기

대상:

- `GET /api/v1/courses/{courseId}`
- `GET /api/v1/courses/{courseId}/route-points`
- `POST /api/v1/courses/{courseId}/ride-policy/evaluate`

후보:

- route snapshot cache를 다중 서버에서도 공유 가능한 Redis 또는 DB snapshot으로 승격한다.
- 경로점이 수천 개 이상인 코스는 encoded polyline snapshot을 별도로 둔다.
- ride-policy 계산은 현재 index 기반 최적화가 들어가 있으므로, 다음 단계는 실제 긴 route 기준 p95 측정 후 공간 index/R-tree를 검토한다.

트레이드오프:

- Redis/DB snapshot은 cache invalidation 정책이 필요하다.
- encoded polyline은 응답 생성은 빨라지지만 point 단위 메타데이터 확장에는 불리할 수 있다.

### 자유주행 기록

대상:

- `POST /api/v1/ride-records`
- `GET /api/v1/ride-records`
- `GET /api/v1/ride-records/{rideRecordId}`

후보:

- point batch insert의 batch size와 flush 단위를 실제 기기 GPX 길이 기준으로 측정한다.
- 기록 목록은 owner/endedAt/id 인덱스와 cursor pagination을 더 강하게 맞춘다.
- 상세 응답이 route point까지 커지면 summary/detail 분리 또는 point paging을 검토한다.

트레이드오프:

- batch insert를 키우면 처리량은 좋아질 수 있지만 트랜잭션 메모리와 실패 rollback 비용이 커진다.
- point paging은 프론트 따라가기 UX와 API 계약을 같이 바꿔야 한다.

### AI 코스 생성

대상:

- `POST /api/v1/ai-routes/plan`
- `WS /ws/v1/ai-routes`

후보:

- GraphHopper warm-up과 route cache를 분리한다.
- AI worker timeout, provider 실패 reason, retry/circuit breaker 지표를 추가한다.
- 고도/경사/정석 루트 evidence 계산을 provider 응답 파싱 단계와 score 계산 단계로 분리한다.

트레이드오프:

- cache는 비용과 latency를 줄이지만, 목적지/선호도별 cache key 설계가 까다롭다.
- retry는 성공률을 올릴 수 있지만 p95/p99와 provider 비용을 늘릴 수 있다.

### 주소 검색/날씨/최근 위치

대상:

- `GET /api/v1/addresses/search`
- `GET /api/v1/weather/current`
- `GET /api/v1/location/me/recent`

후보:

- 주소 검색은 provider rate-limit과 debounce를 프론트/백엔드 양쪽에서 맞춘다.
- 날씨는 현재 cache/stale fallback이 있으므로 지역 격자 key 정밀도와 TTL을 운영 지표로 조정한다.
- 최근 위치는 Redis miss 후 DB fallback 조회가 있으므로 owner/status/endedAt 인덱스 효과를 확인한다.

트레이드오프:

- TTL을 늘리면 비용은 줄지만 최신성이 떨어진다.
- 주소 검색 cache는 raw query 저장 금지 정책과 충돌하지 않도록 key 익명화가 필요하다.

### 이벤트/운영 모니터링

대상:

- `POST /api/v1/events`
- `POST /api/v1/events/batch`
- `GET /health/monitor`

후보:

- 이벤트 batch 저장은 JDBC batch와 payload size 제한을 k6로 재측정한다.
- monitoring은 외부 dependency별 timeout을 짧게 유지하고, 상세 결과는 OPS 권한으로만 제공한다.

트레이드오프:

- 이벤트 batch 크기를 키우면 처리량은 올라가지만 단일 요청 실패 영향이 커진다.
- monitoring을 자주 호출하면 자체가 DB/Redis에 부하가 될 수 있다.

## 다음 개선 순서 제안

1. 실제 k6 시나리오에 `profile/activity-summary`를 추가하는 작업은 완료했다. 로컬 short baseline 기준 `profile-read` p95는 5.14ms였다.
2. `featured courses`의 id 재조회 구조는 `where id in (...)` batch fetch로 줄였다. 다음 단계는 PostGIS `EXPLAIN ANALYZE`로 실제 query plan을 확인하는 것이다.
3. AI route는 GraphHopper warm-up/cache와 worker 실패 reason metric을 먼저 안정화한다.
4. event batch는 저장량이 늘어난 뒤 slow query와 DB CPU 기준으로 batch size를 조정한다.
