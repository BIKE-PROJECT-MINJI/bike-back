# BIKE Backend

Spring Boot 백엔드 저장소다.
제품, 정책, API, 인프라, ADR, 운영 검증, 장애 대응, USM의 최종 원본은 이 저장소 내부 문서가 아니라 루트 `DOCS/개발용` 문서 묶음이다.

## 원본 문서

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

환경 변수와 외부 provider 설정은 루트 `DOCS/개발용/04_데이터_인프라_아키텍처.md`와 실제 배포 환경의 secret store를 기준으로 확인한다.
비밀값은 README나 Markdown에 평문으로 쓰지 않는다.

## 로컬 30분 온보딩

1. 현재 브랜치와 원격을 확인한다.

```bash
git status --short --branch
git remote -v
```

2. JDK/Gradle 빌드가 되는지 확인한다.

```bash
./gradlew --no-daemon clean check
```

3. 로컬 실행 전 필요한 DB/Redis/GraphHopper 구성은 루트 `DOCS/개발용/04_데이터_인프라_아키텍처.md`를 확인한다.

4. 앱을 실행한다.

```bash
./gradlew bootRun
```

5. Swagger/OpenAPI와 health를 확인한다.

```bash
curl -fsS http://127.0.0.1:8080/health
curl -i http://127.0.0.1:8080/health/monitor
```

6. smoke, k6, AWS 테스트는 루트 `DOCS/개발용/07_운영_장애_테스트_대응록.md`의 순서와 중단 기준을 따른다.

## 검증

```bash
./gradlew --no-daemon clean check
```

AWS를 켜기 전 Hybrid 개발 레인 preflight는 아래 명령으로 확인한다.

```bash
./ops/smoke/run-hybrid-preflight.sh
```

이 명령은 compose config, AWS wrapper 문법, targeted backend test, AI route worker pytest를 비용 없이 확인하고 `ops/smoke/results/`에 evidence를 남긴다.

Neon/Upstash/터널을 붙인 뒤 실제 기기 접근 경로는 아래 HTTP smoke로 확인한다.

```bash
BIKE_SMOKE_BASE_URL=http://127.0.0.1:8080 ./ops/smoke/run-hybrid-device-smoke.sh
BIKE_SMOKE_BASE_URL=https://replace-with-device-tunnel-host ./ops/smoke/run-hybrid-device-smoke.sh
```

이 명령은 Docker DB/Redis 내부 상태에 의존하지 않고 `health`, 코스 목록, 주소 검색, 회원가입/로그인, AI 경로 생성, 주행 summary 저장 API 계약과 `X-Request-Id`/`X-Trace-Id` evidence를 `ops/smoke/results/`에 남긴다.

운영 smoke, k6, AWS 검증 순서와 중단 기준은 루트 `DOCS/개발용/06_개발_운영_검증.md`와 `07_운영_장애_테스트_대응록.md`를 따른다.

## Git 기준

- 작업 시작 전 `git status --short --branch`와 `git remote -v`를 확인한다.
- 기능/API 변경은 가능한 한 작은 브랜치와 PR로 나눈다.
- API, DB, 운영, 장애 대응, 사용자 시나리오 기준이 바뀌면 루트 `DOCS/개발용` 문서 동기화 여부를 확인한다.

## 이 저장소 안에서 원본으로 보지 않는 것

- 과거 `docs/` 하위 ADR/인프라/검토 Markdown
- `.omo/` 초안
- 임시 보고서나 중간 계획서

필요한 기준성 내용은 루트 `DOCS/개발용` 문서로 흡수하고, 이 저장소에는 코드, 테스트, 실행 스크립트, raw evidence만 남긴다.
