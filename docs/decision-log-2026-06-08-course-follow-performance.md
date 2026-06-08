# Decision log: course-follow performance

## 사용자 요구 요약

- 백엔드와 웹/테스트 흐름을 GitHub로 관리한다.
- 구현 전 API 명세, 정책, 주변 영향, TDD, 코드 리뷰 기준을 문서화한다.
- 성능 개선은 local test, EC2 test, k6 100VU, before/after 측정, 개선율 보고까지 포함한다.
- AWS EC2 리소스는 테스트 후 반드시 삭제한다.
- 비개발자도 이해할 수 있는 최종 보고서를 남긴다.

## 이번 작업 범위 결정

- 프론트 웹 기능 추가가 아니라 `course-follow hot path` 백엔드 성능 개선을 우선 처리했다.
- 이유: 기존 AWS 100VU evidence에서 `course-follow` p95/p99가 가장 큰 병목으로 분리되어 이슈 #37이 열려 있었다.
- AI route 전체 품질/credential 문제는 이번 hot path와 분리했다.

## 주요 의사결정

1. route-points cache miss lock을 전역 lock에서 courseId 단위 계산으로 변경
   - 이유: 다른 코스의 route-points 요청까지 한 줄로 기다리는 구조가 p95/p99를 키웠다.

2. finalization과 regenerate 동시성은 DB row lock으로 보호
   - 이유: 상태 전이와 processed point 재생성이 같은 rideRecordId에서 경쟁하면 중복키 오류가 난다.

3. READY 상태 finalization은 skip
   - 이유: 이미 확정된 기록을 async worker가 다시 쓰면 데이터 정합성을 해칠 수 있다.

4. finalization 실패 상태 기록은 별도 트랜잭션으로 분리
   - 이유: processed point 교체 중 실패하면 교체 트랜잭션은 rollback되어 기존 경로가 남아야 하고, 실패 상태만 별도 저장되어야 한다.

5. 코스 생성 후 업적 부여는 commit 이후 bounded async executor로 이동
   - 이유: 사용자 응답에 필요한 핵심 작업과 부가 작업을 분리해 트랜잭션 부담을 줄인다.

6. Spring async executor와 테스트 compose DB pool을 명시
   - 이유: 기본 async executor는 부하에서 스레드를 과도하게 늘릴 수 있고, Hikari 기본 10개 pool은 100VU 테스트에서 후처리와 요청이 함께 몰릴 때 병목이 된다.

7. AWS 테스트는 임시 EC2 생성/실행/삭제 스크립트로 자동화
   - 이유: 비용 리스크를 낮추고 같은 조건의 before/after evidence를 반복 생성하기 위해서다.

## 확인된 한계

- EC2 r5 compose 테스트에서는 AI route checks가 실패했고, 이후 로컬 Gemini/GraphHopper key smoke에서도 `from-text` endpoint는 200을 반환하지 못했다.
- course-follow와 free-ride checks는 after에서 실패하지 않았다.
- GraphHopper CPU가 높아 AI route/전체 p99 개선은 별도 작업이 필요하다.
- AI route 포함 100VU 혼합 capacity는 후속 작업이다. 이번 성능 PR은 course/free hot path 100VU 통과를 기준으로 완료했다.
