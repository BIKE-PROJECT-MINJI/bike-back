# Draft: 후속 작업 딥인터뷰

## Requirements (confirmed)

- 사용자는 현재 작업물을 기준으로 다음 후속 작업을 정하고 싶다.
- PR 제목/브랜치명/커밋 설명은 사용자 본인 작업처럼 보여야 하므로 `codex` 표기를 제거했다.
- 커밋/PR 메시지는 한글을 기본으로 한다.
- 후속 작업은 포트폴리오 가치, 성능 수치, 운영 안정성, 비용을 함께 고려해야 한다.

## Technical Decisions

- 현재 PR은 `course-performance-stability` 브랜치와 PR #41로 열려 있다.
- 최근 완료된 작업은 AI 생성 세션화, 코스 목록 projection 경량화, `CourseQueryService` 분리, GraphHopper route readiness/cache 기본 보존이다.
- 다음 후보는 재측정, GraphHopper cache artifact, AI 비동기 큐, 목록 read model, PR 분할/리뷰 대응이다.

## Research Findings

- 이전 EC2 100VU 결과: 전체 p95 14.23초, p99 15.82초, 체크 성공률 86.91%.
- 코스 목록 p95 14.56초, AI route session 생성 p95 16.91초.
- GraphHopper는 테스트 시점에 OSM/SRTM 준비 중이었고 CPU 134.40%, 메모리 2.259GiB를 사용했다.
- 2026-06-12 오후 개선 후 EC2 재측정을 시도했지만, 실제 성능 수치 이전에 하네스 결함을 먼저 발견했다.
- 하네스 결함: `/actuator/health` 401 오인, `/health` 호출 포트 오류, 실패 시 원격 로그 수집 부족, k6 결과 파일 누락을 성공처럼 넘길 위험.
- 하네스 수정 후 디버그 실행에서는 `GET /health`는 200으로 통과했지만 GraphHopper route readiness가 `000`으로 실패했다.
- 모든 EC2 실행은 cleanup receipt 기준으로 instance/security group/key pair 삭제가 확인됐다.
- 현재 PR은 66개 파일 변경으로 크다.

## Open Questions

- 다음 목표를 PR 병합 안정화로 둘지, 재측정 수치 확보로 둘지, 추가 구현으로 둘지 정해야 한다.
- EC2 재측정 비용을 감수할지, 로컬/k6 smoke만 먼저 할지 정해야 한다.
- GraphHopper 장기 cache 전략을 tar artifact, EBS snapshot, AMI 중 어디까지 구현할지 정해야 한다.
- AI 코스 생성을 동기 세션에서 비동기 큐/polling으로 더 분리할지 정해야 한다.

## Priority Decision After B

1. C: fresh EC2에서도 GraphHopper import 시간을 제거할 수 있는 cache artifact 전략을 먼저 잡는다.
2. E: 코스 목록 전용 read model을 도입해 목록 조회를 더 가볍게 만든다.
3. D: AI route 생성은 비동기 queue/polling으로 분리한다.

이유: 현재 측정은 GraphHopper가 route readiness를 통과하지 못해 k6가 시작되기 전에 차단된다. 즉, 목록 read model이나 AI 큐 개선을 측정하려면 먼저 GraphHopper 준비 시간을 비용 낮게 안정화해야 한다.

## Scope Boundaries

- INCLUDE: 후속 작업 우선순위 결정, 포트폴리오 수치화 방향, 비용 고려, PR 운영 전략.
- EXCLUDE: 이번 인터뷰 답변 전 추가 코드 구현.
