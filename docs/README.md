# 백엔드 문서 안내

마지막 수정일: 2026-06-26

이 폴더는 `dev/bike-back` 저장소 안에서 백엔드 구현자가 참고해야 하는 ADR, 검토, 인프라 문서를 둔다.
새 최종 보고서는 워크스페이스 루트의 `reports/백엔드/` 또는 `reports/리뷰_QA/`에 작성한다.

## 폴더 기준

| 위치 | 역할 |
|---|---|
| `ADR/` | 백엔드 기술 선택, 구조 선택, 성능 개선 판단 기록 |
| `검토/` | 기능별 리뷰, 아키텍처 리뷰, 개선 후보 정리 |
| `인프라/` | 로컬 런타임, Docker Compose, AWS, k6, GitHub 동기화 가이드 |

## 보고서 위치

| 보고서 | 위치 |
|---|---|
| 백엔드 기능/API/Swagger 검증 | `../../../reports/백엔드/` |
| 보안/QA/리뷰 검증 | `../../../reports/리뷰_QA/` |
| 테스트 원본 evidence | `../../../ARTIFACTS/test-reports/`, `../../../ARTIFACTS/qa-evidence/` |
| 레거시 세션 보고서 | `../../../쓰레기통/2026-06-26_harness_simplification/session_handoff_legacy/` |

`dev/bike-back/docs/`에는 새 `*-report-YYYY-MM-DD.md` 파일을 만들지 않는다.
