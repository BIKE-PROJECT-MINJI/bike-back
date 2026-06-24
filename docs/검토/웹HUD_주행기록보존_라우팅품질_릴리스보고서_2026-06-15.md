# 웹 HUD/주행기록 보존/라우팅 품질 마무리 보고서

작성일: 2026-06-15

## 목표

- 웹 테스트 콘솔에서 게스트도 AI 코스 생성, HUD 경로 표시, GPX/주행 저장 흐름을 확인할 수 있게 한다.
- 주행기록은 사용자가 직접 삭제할 수 있고, 30일 보존 정책에 따라 만료 데이터를 정리한다.
- GraphHopper 또는 라우팅 provider fallback 여부와 경로 품질 상태를 응답에 드러낸다.
- 로컬 기준 성능 목표를 통과한다.

## 구현 요약

- `DELETE /api/v1/ride-records/{rideRecordId}`를 추가했다.
  - 본인 주행기록만 삭제한다.
  - 주행 원본 점, 보정 점, 연결된 Course source 참조를 함께 정리한다.
- 30일 지난 주행기록 정리 스케줄러를 추가했다.
  - 기본 cron: `0 20 3 * * *`
  - 기준 시각은 `Clock` bean으로 주입해 테스트 가능하게 했다.
- 라우팅 결과에 품질/폴백 메타데이터를 추가했다.
  - provider, fallback 사용 여부, fallback 사유, 품질 상태, 품질 메시지를 응답에 포함한다.
  - 고도 정보가 없으면 실패로 숨기지 않고 `VALID_WITH_WARNINGS`로 표시한다.
- 로컬 성능 검증용 fake weather provider를 추가했다.
  - 외부 Open-Meteo 지연이 로컬 API 성능 검증을 흔들지 않게 분리했다.
- 로컬 웹 콘솔 CORS를 허용했다.
  - 기본 허용 origin: `http://127.0.0.1:8081`, `http://localhost:8081`
  - 운영/배포 시 `APP_CORS_ALLOWED_ORIGINS`로 변경한다.

## 성능 결과

환경:

- Backend: `http://127.0.0.1:8080`
- DB: local Docker PostgreSQL `bike_perf`
- Redis: local Docker Redis
- Routing/weather: fake provider
- 측정 도구: k6

| 시나리오 | 목표 | 결과 |
| --- | ---: | ---: |
| 일반 API 50명 혼합 p95 | 300ms 이하 | 16.15ms |
| 코스 목록/지도 조회 p95 | 200ms 이하 | course-read 16.51ms |
| route-points 조회 p95 | 300ms 이하 | 15.78ms |
| weather 조회 p95 | 300ms 이하 | 16.02ms |
| ride-policy p95 | 300ms 이하 | 15.98ms |
| AI 코스 생성 50명 동시 p95 | 10초 이하 | 116.96ms |
| 에러율 | 1% 이하 | 0% |

증거 파일:

- `ops/loadtest/results/local-p6-mixed-50-final-20260615-summary.json`
- `ops/loadtest/results/local-ai-public-50-final-20260615-summary.json`

## 웹 QA 결과

- `http://127.0.0.1:8081`에서 웹 콘솔을 실행했다.
- API Base를 `http://127.0.0.1:8080`으로 설정했다.
- 게스트 데모 버튼으로 고정 경로가 HUD와 지도에 표시됨을 확인했다.
- 토큰 없는 상태에서 텍스트 코스 생성을 실행했고, 공개 API `/api/v1/ai-routes/plan/from-text`가 성공했다.
- HUD에 route points, route km, score, elevation, routing metadata가 표시됐다.
- 브라우저 콘솔 error는 0건이었다.

## 남은 리스크

- 이번 로컬 성능 검증은 fake routing/weather 기준이다. 실제 GraphHopper self-host와 외부 AI worker를 켠 운영형 검증은 별도 EC2/비용 계획에 맞춰 다시 측정해야 한다.
- 웹 콘솔은 현재 별도 git repo가 아니므로 백엔드 PR에는 포함되지 않는다.
- Vite 번들 크기 경고가 남아 있다. MapLibre/Turf를 lazy import하면 첫 로드 비용을 더 낮출 수 있다.
