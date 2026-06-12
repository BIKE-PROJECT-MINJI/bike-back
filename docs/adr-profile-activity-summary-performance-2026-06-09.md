# ADR: profile activity-summary 집계 성능 개선

- 날짜: 2026-06-09
- 상태: accepted
- 범위: `GET /api/v1/profile/me/activity-summary`, ride/course 집계 repository, Flyway index

## 배경

`/api/v1/profile/me/activity-summary`는 마이페이지와 홈 요약에서 반복 호출될 가능성이 높은 읽기 API다.
기존 구현은 한 번의 응답을 만들기 위해 ride 집계 6회, course 집계 2회를 각각 호출했다.

- 주간 주행 수
- 주간 거리 합
- 주간 시간 합
- 주간 저장 코스 수
- 전체 주행 수
- 전체 거리 합
- 전체 시간 합
- 기존 구현에서 조회했지만 응답 DTO에는 쓰이지 않던 전체 저장 코스 수

데이터가 적을 때는 문제가 작지만, 사용자별 주행 기록이 쌓이면 같은 조건을 여러 번 스캔하는 비용이 커진다.

## 선택지

### A. 기존 count/sum 쿼리를 유지하고 인덱스만 추가

장점:
- 변경 범위가 가장 작다.
- 응답 DTO와 Service 구조를 거의 건드리지 않는다.

단점:
- DB round trip 수는 여전히 8회다.
- 같은 owner/status/date 조건을 반복 스캔한다.

트레이드오프:
- 운영 위험은 낮지만 체감 성능 개선 폭이 제한된다.

### B. activity summary 전용 projection query로 집계를 묶고 보조 인덱스를 추가

장점:
- DB round trip을 8회에서 2회로 줄인다.
- DTO 계약은 그대로 유지한다.
- profile Service는 유스케이스 조립 책임만 유지하고, 집계 책임은 repository projection으로 분리된다.

단점:
- JPQL projection이 repository에 추가되어 쿼리가 다소 길어진다.
- 인덱스 추가로 ride/course 저장 시 인덱스 갱신 비용과 저장 공간이 조금 늘어난다.

트레이드오프:
- MVP 범위에서는 가장 균형이 좋다.
- 대규모 통계/랭킹까지 확장하면 별도 summary table로 다시 옮길 수 있다.

### C. activity summary materialized table을 별도로 만든다

장점:
- 읽기 API는 가장 빠르게 만들 수 있다.
- 주간/월간/누적 통계를 다양하게 확장하기 쉽다.

단점:
- 주행 저장, 코스 생성, 삭제, 계정 삭제와 summary 정합성을 맞춰야 한다.
- 동시성, 재계산 job, 장애 복구 정책이 필요하다.

트레이드오프:
- 읽기 성능은 좋지만 현재 기능 규모에는 운영 복잡도가 과하다.

## 결정

B를 적용했다.

- `RideRecordActivityAggregate`: READY 주행의 전체/주간 count, distance, duration을 한 번에 조회한다.
- `CourseActivityAggregate`: 응답에 필요한 주간 저장 코스 count만 조회한다.
- `ProfileService`: 기존 개별 집계 호출 대신 aggregate 2개를 받아 응답 DTO로 변환한다.
- `V21__add_activity_summary_indexes.sql`: `ride_records(owner_user_id, finalization_status, ended_at)`, `courses(owner_user_id, created_at)` 인덱스를 추가한다.

## 결과

- `/api/v1/profile/me/activity-summary`의 DB 집계 호출 수를 8회에서 2회로 줄였다.
- 2026-06-11 보강 검증에서 Hibernate statistics 기준 prepared statement 수가 실제 2개임을 통합 테스트로 고정했다.
- API 응답 필드, 상태 코드, 인증 계약은 바꾸지 않았다.
- 주간 기준은 기존과 동일하게 한국 시간 월요일 00:00부터 일요일 23:59:59.999999999까지다.
- 고도값은 기존 계약대로 provider 확정 전 placeholder `0`을 유지한다.

## 향후 여파

- 사용자가 많아져 write 부하가 커지면 추가 인덱스의 저장 비용을 slow query log와 DB CPU로 다시 확인해야 한다.
- activity summary가 월간/연간/성취/랭킹까지 확장되면 materialized summary table 또는 event-driven summary 업데이트를 재검토한다.
- 계정 삭제나 데이터 익명화 정책이 확장되면 summary table 방식은 정합성 부담이 커진다.
