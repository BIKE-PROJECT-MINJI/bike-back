# 2026-06-08 백엔드 기능/아키텍처 리뷰 색인

이번 리뷰는 세 가지 사용자 기능과 전체 백엔드 구조를 분리해서 남겼다.

## 기능별 문서

- AI 코스 생성: `docs/ai-route-generation-review-2026-06-08.md`
- 코스 따라가기: `docs/course-follow-review-2026-06-08.md`
- 자유주행모드: `docs/free-ride-mode-review-2026-06-08.md`

## 전체 구조 리뷰

- 아키텍처, DB, N+1, 락, WebSocket 리뷰: `docs/backend-architecture-db-websocket-review-2026-06-08.md`

## 짧은 결론

- 이번에 성능 개선이 확실히 검증된 범위는 코스 따라가기와 자유주행모드다.
- AI 코스 생성은 GraphHopper/고도/AI worker 구조가 연결되어 있지만, `from-text` 안정화와 AI route 전용 부하테스트가 후속으로 필요하다.
- WebSocket은 현재 AI 코스 생성 전용 `/ws/v1/ai-routes`에서 사용한다.
- 전형적인 JPA lazy loading N+1 위험은 낮지만, 일부 반복 조회와 큰 서비스 파일은 개선 후보로 남아 있다.
