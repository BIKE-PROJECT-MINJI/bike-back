# 읽기 API 쿼리 개선 및 로컬 성능 측정 보고서

- 날짜: 2026-06-11
- 범위: `profile/activity-summary`, `featured courses`, k6 read/profile 측정
- 인프라 범위: 이번 작업에서는 EC2/무중단 배포/Redis LBS 같은 인프라 변경을 제외했다.

## 한 줄 요약

자주 호출되는 읽기 API를 포트폴리오에서 설명할 수 있게 `profile/activity-summary`는 HTTP 부하 측정 대상에 넣고, `featured courses`는 추천 코스 수만큼 entity를 다시 조회하던 구조를 batch fetch로 줄였다.

## 적용한 개선

### 1. `GET /api/v1/profile/me/activity-summary` 측정 가능화

기존 상태:

- repository 쿼리 수 개선은 완료되어 있었지만 k6 시나리오에는 profile summary가 없었다.
- 그래서 "SQL 호출 수를 8회에서 2회로 줄였다"는 근거는 있었지만, HTTP p95/p99 측정이 바로 나오지 않았다.

변경:

- k6 `profile` 페르소나를 추가했다.
- `profile-read` threshold를 추가했다.
- `AUTH_AUTO_REGISTER=true`이면 k6 `setup()`이 테스트 계정을 로그인하거나 등록해서 토큰을 준비한다.
- 토큰이 없고 자동 등록도 꺼져 있으면 profile/write 페르소나는 실행 대상에서 제외되게 했다.

왜 이렇게 했나:

- 로컬/EC2 어디서든 같은 스크립트로 profile summary p95/p99를 뽑기 위해서다.
- 수동으로 JWT를 복사하는 방식은 재현성이 낮고, 같은 테스트를 다시 돌릴 때 실수하기 쉽다.

트레이드오프:

- k6 `setup()`에서 인증 API를 호출하므로, 요약 JSON에 setup 요청도 일부 포함된다.
- 인증 자체를 별도 부하 대상으로 볼 때는 auth 전용 시나리오로 분리하는 것이 더 정확하다.

### 2. `GET /api/v1/courses/featured` 쿼리 개선

기존 상태:

- PostGIS native query로 가까운 추천 코스 id와 거리를 가져온 뒤, 각 id마다 `entityManager.find()`를 호출했다.
- 추천 limit이 10이면 native query 1회 + entity 조회 최대 10회 구조가 된다.

변경:

- native query는 id와 거리만 가져온다.
- id 목록을 `where id in :ids`로 한 번에 batch fetch한다.
- native query가 만든 거리순 순서를 응답 후보 조립 단계에서 다시 유지한다.

왜 이렇게 했나:

- API 응답 DTO와 서비스 흐름을 크게 바꾸지 않고 DB 왕복 수를 줄일 수 있다.
- projection으로 바로 응답 DTO를 만들면 더 빠를 수 있지만, repository가 API 응답 형태에 강하게 묶인다.
- 이번 단계에서는 성능 개선과 영향 범위 사이 균형이 batch fetch 쪽이 더 좋다.

트레이드오프:

- native query 1회 + entity batch query 1회 구조라, 완전한 단일 쿼리 최적화는 아니다.
- `where in` 결과 순서는 DB가 보장하지 않으므로, 코드에서 native 결과 순서를 재조립해야 한다.

## 로컬 측정 결과

측정 환경:

- 백엔드: local `bootRun`
- 앱 포트: `8080`
- 관리 포트: `8081`
- DB/Redis: `docker-compose.local.yml`의 PostGIS/Redis
- 테스트 데이터: Flyway seed + k6 테스트 계정
- k6 조합: `home,profile,health`
- GraphHopper/AI/날씨 호출: 제외

### smoke

- `TEST_ID`: `local-read-profile-smoke-relaxed-20260611`
- iterations: 7
- 실패율: 0%
- checks: 100%
- 전체 p95: 45.21ms
- `course-read` p95: 9.01ms
- `profile-read` p95: 15.70ms
- `health` p95: 3.73ms

초기 smoke에서 콜드 스타트 직후 첫 요청 때문에 전체 p95 threshold가 실패했다. 서버가 뜬 직후 재실행하니 정상 통과했다.

### short baseline

- `TEST_ID`: `local-read-profile-baseline-authfix-20260611`
- VU: 총 10명
- ramp up: 5초
- hold: 15초
- ramp down: 5초
- iterations: 977
- 실패율: 0%
- checks: 100%
- 전체 p95: 4.60ms
- `course-read` p95: 3.21ms
- `profile-read` p95: 5.14ms
- `health` p95: 1.82ms

해석:

- 로컬 짧은 baseline에서는 profile summary와 featured/list read가 모두 안정적으로 응답했다.
- 이 수치는 로컬 개발 PC 기준이라 운영 성능 수치로 쓰면 안 된다.
- 포트폴리오에는 "로컬 재현 시나리오에서 실패율 0%, profile-read p95 5.14ms"처럼 검증 근거로 쓸 수 있다.

## 검증

- RED test:
  - `CourseRepositoryImplTest`에서 기존 `entityManager.find()` 반복 구조가 실패함을 확인했다.
- GREEN:
  - batch fetch 구현 후 focused test 통과.
- 정적 검증:
  - `node --check ops/loadtest/k6/bike-api.js`
  - `k6 inspect`로 profile 자동 인증 on/off 시나리오 확인.
- 백엔드 검증:
  - `./gradlew --no-daemon test`
  - `./gradlew --no-daemon build`
- 로컬 HTTP 검증:
  - `GET /health` 200
  - k6 smoke/baseline 실행

## 남은 리스크와 다음 단계

- EC2 측정은 이번 요청에서 인프라를 나중으로 미뤘기 때문에 실행하지 않았다.
- 운영 수치가 필요하면 같은 k6 스크립트로 EC2 20 VU canary 후 100 VU를 진행해야 한다.
- `featured courses`는 실제 PostGIS 쿼리 계획까지 보려면 `EXPLAIN ANALYZE`와 GiST 인덱스 사용 여부를 확인해야 한다.
- 관리 포트 `/actuator/health`는 현재 401을 반환했다. 앱 포트 `/health`는 200이다. 운영 모니터링용 health 공개 정책은 별도로 정리하는 것이 좋다.
