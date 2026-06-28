# BIKE Backend

BIKE/GAJA의 Spring Boot 백엔드 저장소다.
자전거 여행 경로 추천, 코스따라가기 HUD, 자유주행 기록 저장/보정, Party 위치 공유, 운영 관측성을 담당한다.

## 핵심 목표

- 사용자가 `평지`, `한강이 보이는`, `자전거도로 위주` 같은 자연어 조건으로 코스를 추천받는다.
- 추천 후보는 GraphHopper/GIS evidence와 백엔드 scorer를 근거로 점수화한다.
- AI worker는 route point를 직접 만들지 않고, 사용자 의도 정규화와 후보 설명/주의 문구를 보강한다.
- 자유주행 trace는 저장 후 finalization worker가 보정하고, READY 상태가 되면 코스로 승격할 수 있다.
- 코스따라가기 HUD는 route point 단순 최근접이 아니라 route polyline 정사영, 진행률, 이탈, coverage 기반 완주 판정을 사용한다.
- 운영 검증은 smoke, contract, integration, concurrency, load, failure, recovery, observability 순서로 evidence를 남긴다.

## 기술 스택

| 영역 | 기준 |
|---|---|
| Runtime | Java 17, Spring Boot 3.5.x, Gradle |
| DB | PostgreSQL 16, PostGIS, Flyway |
| Cache/Lock | Redis, quota/rate-limit, socket token, idempotency lock |
| Routing | GraphHopper self-host 우선, fake/hosted fallback 후보 |
| AI route | 별도 `bike-ai-route` worker, Gemini provider, backend fallback |
| Observability | Actuator, Prometheus metrics, request id / trace id, k6 evidence |
| Infra | Local compose, Neon/Upstash dev 후보, AWS short evidence gate |

## 주요 기능

| 기능 | 구현/검증 기준 |
|---|---|
| Auth | 이메일/카카오 로그인, refresh token rotation, logout, me/delete |
| Course | 목록, 상세, route points, 공개 범위, 신고/공유/다운로드 후보 |
| Course follow | HUD 진행률, 이탈 후보/경고/복귀, coverage 완주 판정 |
| Ride record | 자유주행 저장, trace 저장, finalization job, 코스 승격 |
| AI route | 주소/텍스트 기반 코스 생성, GraphHopper evidence, AI 설명, fallback metadata |
| Address | Kakao Local 우선, Nominatim fallback |
| Weather | Open-Meteo, 구역 단위 Redis cache, stale fallback, prewarm |
| Party | 생성/참여/나가기/신고, socket token, 위치 공유 |
| Ops | health, monitor, metrics, smoke, k6, AWS cleanup evidence |

## AI 코스생성 방향

현재 기준은 "LLM이 좌표를 만드는 서비스"가 아니다.

1. 사용자의 자연어를 `RoutePreference`로 정규화한다.
   예: `FLAT_FIRST`, `RIVERSIDE`, `BIKE_PATH_FIRST`, `LOW_TRAFFIC`, `LOOP`.
2. 백엔드가 `RoutePreference`를 GraphHopper profile/custom model/weighting 후보로 변환한다.
3. GraphHopper가 route와 detail을 반환한다.
4. 백엔드 scorer가 경사, 고도, 노면, 자전거도로, 수변/공원 근접도 같은 GIS evidence로 점수화한다.
5. AI worker는 이미 검증된 후보에 대한 설명, trade-off, 주의 문구만 생성한다.

운영 제한:

- GraphHopper 호출은 기본 1회, 후보 비교 시 최대 3회로 제한한다.
- hard filter는 안전/통행 가능성에만 사용하고, 사용자 취향은 soft weight로 반영한다.
- 풍경/한강/공원 근거가 별도 GIS layer 없이 근사되면 `PARTIAL` 또는 `UNKNOWN` metadata로 표시한다.
- 고도 요약이 없으면 `elevationStatus=UNAVAILABLE` 같은 명시 상태를 내려야 한다.

## 원본 문서

제품, 정책, API, 인프라, ADR, 운영 검증, 장애 대응, USM의 최종 원본은 이 저장소 내부 `docs/`가 아니라 루트 `DOCS/개발용` 문서 묶음이다.

| 기준 | 위치 |
|---|---|
| 기능/비기능 요구사항 | `/mnt/e/bike-work/bike/DOCS/개발용/01_기능_비기능_요구사항.md` |
| 도메인 정책 | `/mnt/e/bike-work/bike/DOCS/개발용/02_도메인_정책.md` |
| API 명세 | `/mnt/e/bike-work/bike/DOCS/개발용/03_API_명세서.md` |
| 데이터/인프라 아키텍처 | `/mnt/e/bike-work/bike/DOCS/개발용/04_데이터_인프라_아키텍처.md` |
| ADR/기술 결정 | `/mnt/e/bike-work/bike/DOCS/개발용/05_ADR_기술결정.md` |
| 개발/운영/검증 기준 | `/mnt/e/bike-work/bike/DOCS/개발용/06_개발_운영_검증.md` |
| 운영/장애/테스트 대응록 | `/mnt/e/bike-work/bike/DOCS/개발용/07_운영_장애_테스트_대응록.md` |
| USM/사용자 시나리오 | `/mnt/e/bike-work/bike/DOCS/개발용/08_USM_사용자_시나리오.md` |

## 로컬 실행

```bash
cd /mnt/e/bike-work/bike/dev/bike-back
./gradlew bootRun
```

환경 변수와 provider 설정은 루트 `DOCS/개발용/04_데이터_인프라_아키텍처.md`와 실제 secret store를 기준으로 확인한다.
비밀값은 README, Markdown, k6 summary, app log에 평문으로 쓰지 않는다.

## 로컬 30분 온보딩

1. 현재 브랜치와 원격을 확인한다.

```bash
git status --short --branch
git remote -v
```

2. JDK/Gradle 빌드와 테스트를 확인한다.

```bash
./gradlew --no-daemon clean check
```

3. 로컬 DB/Redis/GraphHopper 구성은 루트 `DOCS/개발용/04_데이터_인프라_아키텍처.md`를 확인한다.

4. 앱을 실행한다.

```bash
./gradlew bootRun
```

5. Swagger/OpenAPI와 health를 확인한다.

```bash
curl -fsS http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8080/health/monitor
```

## Smoke / 검증

기본 검증:

```bash
./gradlew --no-daemon clean check
```

AWS를 켜기 전 Hybrid 개발 레인 preflight:

```bash
./ops/smoke/run-hybrid-preflight.sh
```

Neon/Upstash/터널을 붙인 뒤 실제 기기 접근 smoke:

```bash
BIKE_SMOKE_BASE_URL=http://127.0.0.1:8080 ./ops/smoke/run-hybrid-device-smoke.sh
BIKE_SMOKE_BASE_URL=https://replace-with-device-tunnel-host ./ops/smoke/run-hybrid-device-smoke.sh
```

AWS short evidence gate:

```bash
AWS_ROUTING_MODE=fake ./ops/loadtest/run-aws-compose-k6.sh
```

GraphHopper 실 provider drill은 fake smoke와 분리해서 실행한다.
GraphHopper cache restore를 쓰는 경우 `GRAPHHOPPER_CACHE_ARCHIVE_FILE` 또는 `GRAPHHOPPER_CACHE_ARCHIVE_URL`을 명시한다.

## 최근 AWS 검증 요약

| 날짜 | 테스트 | 결과 |
|---|---|---|
| 2026-06-28 | AWS fake routing 3VU smoke | 실패율 0%, p95 약 99ms |
| 2026-06-28 | AWS real GraphHopper 2VU drill | 실패율 0%, p95 약 78ms |
| 2026-06-28 | AWS real GraphHopper 5VU read/HUD drill | 실패율 0%, p95 약 35ms |
| 2026-06-29 | AWS real GraphHopper 10VU read/HUD drill | 실패율 0%, p95 약 43ms |
| 2026-06-29 | AWS fake routing 25VU mixed smoke | 실패율 0%, p95 약 116ms |
| 2026-06-29 | AWS Gemini AI route 1VU provider drill | HTTP 실패율 0%, checks 89.7%, elevation summary 품질 계약 보강 필요 |

상세 evidence와 판단은 `/mnt/e/bike-work/bike/DOCS/개발용/07_운영_장애_테스트_대응록.md`를 기준으로 본다.

## Git 기준

- 작업 시작 전 `git status --short --branch`와 `git remote -v`를 확인한다.
- 기능/API 변경은 가능한 한 작은 브랜치와 PR로 나눈다.
- API, DB, 운영, 장애 대응, 사용자 시나리오 기준이 바뀌면 루트 `DOCS/개발용` 문서 동기화 여부를 확인한다.
- PR 본문에는 변경 요약, 검증 명령, evidence 위치, 남은 리스크를 남긴다.

## 이 저장소 안에서 원본으로 보지 않는 것

- 과거 `docs/` 하위 ADR/인프라/검토 Markdown
- `.omo/` 초안
- 임시 보고서나 중간 계획서

필요한 기준성 내용은 루트 `DOCS/개발용` 문서로 흡수하고, 이 저장소에는 코드, 테스트, 실행 스크립트, raw evidence만 남긴다.
