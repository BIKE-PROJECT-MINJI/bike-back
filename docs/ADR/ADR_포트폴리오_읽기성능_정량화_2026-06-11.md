# ADR: 포트폴리오용 읽기 성능 정량화와 A/B/C 순차 개선

- 날짜: 2026-06-11
- 상태: accepted
- 범위: activity-summary 측정, home/profile read 성능 패키지, featured course query 개선, 이후 AI route 안정화

## 배경

포트폴리오에서 설득력 있게 설명하려면 "개선했다"가 아니라 p95/p99, 처리량, 에러율, DB query 수 같은 정량 수치가 필요하다.

현재 이미 확보한 근거:

- course-follow hot path는 local/EC2 before-after p95/p99 개선 수치가 있다.
- `profile/activity-summary`는 SQL prepared statement 8회성 집계 흐름을 2회로 줄였고, 통합 테스트로 2회를 검증했다.

부족한 근거:

- `profile/activity-summary`의 HTTP p95/p99 측정이 없다.
- home/profile read 흐름으로 묶은 포트폴리오 스토리가 아직 약하다.
- AI route 안정화는 핵심 기능이지만 외부 provider와 GraphHopper 변수가 커서 별도 단계가 필요하다.

## 선택지

### A. activity-summary만 정량화

장점:
- 현재 변경과 바로 연결된다.
- DB query 수 감소와 HTTP p95/p99를 한 API 기준으로 설명하기 쉽다.

단점:
- 포트폴리오 기능 임팩트가 작아 보일 수 있다.

트레이드오프:
- 가장 안전하지만 스토리 범위가 좁다.

### B. home/profile read 성능 패키지로 확장

장점:
- `featured courses`, course list/detail, profile summary를 묶어 "자주 호출되는 읽기 API 최적화"로 설명할 수 있다.
- 기존 리뷰에서 확인된 featured course id 재조회 병목까지 함께 줄일 수 있다.

단점:
- activity-summary보다 변경 파일과 테스트 범위가 늘어난다.

트레이드오프:
- 포트폴리오 설명 가치와 구현 위험의 균형이 가장 좋다.

### C. AI route 안정화까지 바로 포함

장점:
- 프로젝트 핵심 기능의 성공률, p95/p99, fallback rate를 포트폴리오 핵심 스토리로 만들 수 있다.

단점:
- Gemini/AI worker/GraphHopper 변수와 비용 영향이 크다.
- A/B의 읽기 최적화와 성격이 달라 한 PR 안에서 설명이 흐려질 수 있다.

트레이드오프:
- 임팩트는 가장 크지만 1차 정량화 작업에는 과하다.

## 결정

사용자 선택에 따라 A -> B -> C 순차 진행한다.

1. A: `profile/activity-summary`를 k6 profile persona에 포함해 HTTP p95/p99 측정이 가능하게 한다.
2. B: home/profile read 패키지에서 `featured courses`의 native query 후 N번 `entityManager.find()` 반복을 batch entity fetch로 줄인다.
3. C: AI route 안정화와 GraphHopper/worker 측정은 다음 단계로 분리한다.

측정 기준:

- before 기준은 `origin/main`으로 한다.
- 측정은 로컬과 EC2를 모두 대상으로 한다.
- EC2는 20 VU canary 후 100 VU 본측정을 진행한다.
- AWS wrapper의 cleanup receipt를 완료 증거로 남긴다.

## B 구현 방식 결정

선택: native query는 id/distance만 가져오고, `where id in (...)` batch fetch로 entity를 한 번에 조회한다.

대안:

- native query에서 응답 projection까지 직접 구성
- 기존 `entityManager.find()` 반복 유지

선택 이유:

- API 응답 DTO와 `CourseService` 변환 흐름을 크게 바꾸지 않는다.
- `limit`이 늘어날 때 N번 entity 조회가 커지는 문제를 줄인다.
- repository custom 구현 내부 변경으로 영향 범위를 좁힌다.

## 인프라 결정

이번 구현에서는 인프라를 변경하지 않는다.

후보군만 남긴다.

- EC2 app + RDS + ElastiCache로 상태 저장소 분리
- Prometheus/Grafana + k6 remote write로 관측 고도화
- ALB + 2 EC2 rolling/blue-green으로 무중단 배포
- ECS/Fargate 전환

## 검증 계획

- RED: `CourseRepositoryImpl` 단위 테스트에서 native query 결과 id 순서가 batch fetch 후에도 유지되고, `entityManager.find()` 반복이 호출되지 않음을 검증한다.
- GREEN: batch fetch 구현.
- k6: `profile` persona와 `profile-read` threshold 추가 후 `node --check` 또는 k6 inspect로 스크립트 정합성을 확인한다.
- Gradle: focused test, 전체 test, build.
- 측정: 로컬 smoke/baseline 후 EC2 20 VU canary, 이상 없으면 100 VU.

## 남은 리스크

- `featured courses` PostGIS native query는 H2에서 직접 실행하기 어렵다. repository 구현 테스트는 Mockito로 query orchestration을 고정하고, 실제 PostGIS 경로는 k6/EC2 smoke로 보완한다.
- EC2 측정은 비용이 발생한다. cleanup receipt가 남아야 완료로 본다.
- AI route 안정화는 이번 A/B 구현이 끝난 뒤 별도 ADR로 진행한다.
