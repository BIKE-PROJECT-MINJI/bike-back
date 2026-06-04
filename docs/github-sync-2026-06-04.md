# GitHub 원격 동기화 코멘트 - 2026-06-04

## 목적

사용자 지시에 따라 로컬 `dev/bike-back` 작업 내용을 GitHub 원격 `BIKE-PROJECT-MINJI/bike-back`에 반영한다.

## 변경 묶음

- 백엔드 출시 차단 조건 보강
- auth 정책 보강
- profile preference 저장
- course report/temporary hide
- ride/course e2e hardening
- achievement MVP 3종 지급/조회
- routing, observability, 운영 문서와 smoke 보강

## 원격 반영 방식

- 기준 브랜치: `main`
- 원격 백업 브랜치를 만든 뒤 로컬 기준 커밋을 원격에 반영한다.
- 비밀값 후보는 push 전 스캔한다.

## 검증 메모

- ulw-loop evidence 기준 G001~G005는 pass/complete 상태다.
- 세부 evidence는 워크스페이스 `.omo/ulw-loop/evidence/`와 `.sisyphus/evidence/backend-future-development/`에 있다.
