# k6 기반 백엔드 부하테스트 시작 가이드

## 1. 목적

이 문서는 `bike-back`에서 **가장 작은 시작점**으로 k6를 붙여,
핵심 API의 smoke / baseline / stress 부하테스트를 반복 가능하게 만드는 가이드입니다.

현재 starter는 **페르소나 기반 시나리오**로 구성돼 있습니다.
즉 각 시나리오는 “이 행동을 하는 사용자 집단”을 뜻하고,
baseline / stress에서는 이 집단들이 **동시에 섞여서 들어오는 부하**를 상정합니다.

현재 기본 방향은 아래입니다.

- 전체 API를 한 번에 넓게 때리지 않습니다.
- 핵심 read 경로를 먼저 봅니다.
- 그 다음에 `ride-policy/evaluate`, 인증 기반 read, write 경로를 추가합니다.
- `/health`는 생존 확인용이고, 병목 분석은 request_id 로그 / DB / Redis / 외부 API 지연과 함께 봅니다.

## 2. 파일 위치

- 스크립트: `ops/loadtest/k6/bike-api.js`
- 환경변수 예시: `ops/loadtest/k6.env.example`

## 3. 사전 준비

1. k6를 로컬 또는 실행기에서 설치합니다.
2. backend가 기동 중이어야 합니다.
3. 필요한 경우 테스트용 `AUTH_BEARER_TOKEN`을 준비합니다.
4. `COURSE_ID`를 비워두면 `setup()`이 `/api/v1/courses/featured` 또는 `/api/v1/courses?limit=1`에서 첫 코스를 자동 탐색합니다.

## 4. 빠른 시작

### 4-1. smoke 실행

```bash
BASE_URL=http://localhost:8080 \
k6 run ops/loadtest/k6/bike-api.js
```

실행 후 요약 JSON은 기본적으로 `ops/loadtest/results/<TEST_ID>-summary.json`에 저장됩니다.

### 4-2. env 파일 복사 후 실행

```bash
cp ops/loadtest/k6.env.example ops/loadtest/k6.env
set -a
source ops/loadtest/k6.env
set +a
k6 run ops/loadtest/k6/bike-api.js
```

### 4-3-1. 운영-safe read baseline 예시

프론트 이슈와 분리해서 **백엔드 read 경로만** 먼저 보고 싶으면 아래처럼 `write` 페르소나와 인증 토큰을 빼고 실행합니다.

```bash
TEST_ID=prod-read-baseline-001 \
SCENARIO=baseline \
PERSONAS=home,preRide,health \
BASE_URL=https://gajabike.shop \
BASELINE_TOTAL_VUS=6 \
BASELINE_RAMP_UP=1m \
BASELINE_HOLD=3m \
BASELINE_RAMP_DOWN=1m \
k6 run ops/loadtest/k6/bike-api.js
```

### 4-3-2. 100명 동시 사용 가정 mixed scenario 예시

`100명 사용`은 현재 BIKE 기준에서 **100 RPS**가 아니라 **100 concurrent active sessions(VUs)** 로 해석하는 것이 가장 현실적입니다.
즉 사용자가 think time을 두고 홈/주행 전/헬스 경로를 섞어 쓰는 closed-model 테스트를 뜻합니다.

```bash
TEST_ID=prod-mixed-100-users-001 \
SCENARIO=stress \
PERSONAS=home,preRide,inRide,health \
BASE_URL=http://3.35.168.38 \
STRESS_TOTAL_VUS=100 \
STRESS_RAMP_UP=3m \
STRESS_HOLD=7m \
STRESS_RAMP_DOWN=3m \
HOME_WEIGHT_PERCENT=35 \
PRERIDE_WEIGHT_PERCENT=35 \
INRIDE_WEIGHT_PERCENT=20 \
HEALTH_WEIGHT_PERCENT=10 \
WRITE_WEIGHT_PERCENT=0 \
k6 run ops/loadtest/k6/bike-api.js
```

이 조합은 현재 프론트 이슈와 분리해서 **백엔드 read/HUD 성격 경로**를 우선 확인하는 목적에 맞습니다.
운영 영향이 있는 write 경로는 기본값으로 꺼 둡니다.

### 4-3-3. Grafana에 k6 시계열까지 같이 띄우는 실행 예시

Prometheus remote write receiver가 켜져 있으면, k6 결과를 같은 Grafana/Prometheus 축으로 함께 볼 수 있습니다.

```bash
TEST_ID=prod-mixed-100-users-001 \
SCENARIO=stress \
PERSONAS=home,preRide,inRide,health \
BASE_URL=http://3.35.168.38 \
STRESS_TOTAL_VUS=100 \
STRESS_RAMP_UP=3m \
STRESS_HOLD=7m \
STRESS_RAMP_DOWN=3m \
HOME_WEIGHT_PERCENT=35 \
PRERIDE_WEIGHT_PERCENT=35 \
INRIDE_WEIGHT_PERCENT=20 \
HEALTH_WEIGHT_PERCENT=10 \
WRITE_WEIGHT_PERCENT=0 \
K6_PROMETHEUS_RW_SERVER_URL=https://observability.gajabike.shop/prom-remote-write \
k6 run -o experimental-prometheus-rw ops/loadtest/k6/bike-api.js
```

권장 사항:

- `TEST_ID`는 반드시 고정해서 Grafana에서 같은 실행 단위를 필터링할 수 있게 둡니다.
- 최초 100-user 실행 전에는 `STRESS_TOTAL_VUS=20` 또는 `50`으로 짧은 canary를 먼저 확인합니다.
- 운영 write 경로는 별도 검증 전까지 끄는 것이 안전합니다.
- `prom-remote-write` 경로는 현재 작업자 IP만 허용하는 방식으로 잠가 둡니다. 장기적으로는 VPN/SSO/사설 경로로 재정리하는 것이 더 좋습니다.

### 4-3. baseline 실행

```bash
SCENARIO=baseline \
BASE_URL=http://localhost:8080 \
BASELINE_TOTAL_VUS=10 \
k6 run ops/loadtest/k6/bike-api.js
```

### 4-4. stress 실행

```bash
SCENARIO=stress \
BASE_URL=http://localhost:8080 \
STRESS_TOTAL_VUS=25 \
k6 run ops/loadtest/k6/bike-api.js
```

### 4-5. 특정 페르소나만 실행

```bash
SCENARIO=baseline \
PERSONAS=preRide,inRide \
BASE_URL=http://localhost:8080 \
BASELINE_TOTAL_VUS=8 \
k6 run ops/loadtest/k6/bike-api.js
```

## 5. 페르소나 시나리오 구조

### P1. home

- `/api/v1/courses/featured`
- `/api/v1/courses?limit=10`
- `COURSE_ID`가 있으면 `/api/v1/courses/{courseId}`

### P2. preRide

- `/api/v1/courses/featured`
- `/api/v1/courses?limit=10`
- `/api/v1/courses/{courseId}`
- `/api/v1/courses/{courseId}/route-points`
- `/api/v1/weather/current`
- `/api/v1/courses/{courseId}/ride-policy/evaluate` (`PRE_START`)

### P3. inRide

- `/api/v1/courses/{courseId}/ride-policy/evaluate`
- `/api/v1/weather/current`
- `AUTH_BEARER_TOKEN`이 있으면 `/api/v1/location/me/recent`

### P4. write

- `AUTH_BEARER_TOKEN`이 있으면 `/api/v1/ride-records`

### P5. health

- `/health`

## 6. 기본 시나리오 범위

이 starter는 아래를 기본 대상으로 둡니다.

- `GET /health`
- `GET /api/v1/courses/featured`
- `GET /api/v1/courses?limit=10`
- `GET /api/v1/weather/current`

아래는 환경변수가 있을 때만 확장됩니다.

- `COURSE_ID`가 있으면
  - `GET /api/v1/courses/{courseId}`
  - `GET /api/v1/courses/{courseId}/route-points`
  - `POST /api/v1/courses/{courseId}/ride-policy/evaluate`
- `AUTH_BEARER_TOKEN`이 있으면
  - `GET /api/v1/location/me/recent`
  - `POST /api/v1/ride-records`

즉 기본값은 **무인증 read 중심 smoke**이고,
필요할 때만 인증/쓰기 경로를 열게 되어 있습니다.

`COURSE_ID`를 비워두면 `setup()`이 featured/list 응답에서 첫 courseId를 자동 탐색하므로, read 시나리오는 별도 준비 없이 시작할 수 있습니다.

## 7. 환경변수 설명

### 필수

- `BASE_URL`: 대상 서버 주소

### 주요 선택값

- `SCENARIO`: `smoke | baseline | stress`
- `PERSONAS`: `home,preRide,inRide,write,health` 중 실행할 페르소나 목록
- `COURSE_ID`: 상세/route-points/ride-policy까지 포함할 코스 id
- `AUTH_BEARER_TOKEN`: 인증이 필요한 recent-location / ride-record save를 테스트할 때 사용
- `TEST_ID`: Grafana/Prometheus/요약 파일을 같은 실행 단위로 묶을 이름
- `SUMMARY_DIR`: `handleSummary()`가 JSON 요약을 저장할 디렉터리
- `K6_PROMETHEUS_RW_SERVER_URL`: Grafana/Prometheus에 k6 시계열을 직접 보내는 Prometheus remote write endpoint
- `P95_MS`, `ERROR_RATE_MAX`: 기본 품질 게이트 조정값

### 페르소나 비중

baseline / stress에서는 아래 비중으로 총 VU를 나눕니다.

- `HOME_WEIGHT_PERCENT`
- `PRERIDE_WEIGHT_PERCENT`
- `INRIDE_WEIGHT_PERCENT`
- `WRITE_WEIGHT_PERCENT`
- `HEALTH_WEIGHT_PERCENT`

즉 `BASELINE_TOTAL_VUS=10`, `HOME_WEIGHT_PERCENT=35`면 home 페르소나에 대략 3~4 VU가 배정됩니다.

## 8. 추천 실행 순서

1. **smoke**
   - 새 스크립트/새 환경에서 먼저 돌립니다.
   - 최소한 endpoint 경로, 인증, 환경변수 실수를 빨리 잡는 용도입니다.
   - 보통 페르소나별 1 VU, 1~2 iteration 정도로 짧게 봅니다.

2. **baseline**
   - 여러 페르소나를 동시에 켜고 정상 상태에서 얼마나 버티는지 봅니다.
   - request_id 기준 access/app log, DB connection, Redis 상태를 같이 봅니다.

3. **stress**
   - baseline이 안정적일 때만 짧게 올립니다.
   - 에러율 급증 지점, policy 계산 지연, weather 외부 API 병목을 찾는 용도입니다.

## 9. 지금 단계에서의 관측 포인트

부하테스트 결과를 볼 때는 평균보다 아래를 우선 봅니다.

- p95 응답시간
- 에러율
- app EC2 CPU / 메모리
- DB connection pool 포화 여부
- 느린 쿼리
- Redis hit/miss 또는 연결 지연
- weather provider 지연과 fallback 흔적
- request_id 기준 access log / app log 추적 가능 여부

## 10. 주의

- `/health`만 보고 성능이 괜찮다고 판단하지 않습니다.
- weather는 외부 API 영향이 있으므로, 서버 병목과 provider 병목을 분리해서 해석합니다.
- `ride-policy/evaluate`는 계산 비용이 있을 수 있으므로, `COURSE_ID`를 넣어 테스트할 때 p95를 별도로 봅니다.
- `ride-record save`는 성능뿐 아니라 정합성과 중복 저장 여부도 같이 봐야 합니다.
- `inRide`, `write` 페르소나는 인증이 없으면 핵심 호출 일부가 생략됩니다. 이 경우 결과를 과하게 낙관적으로 해석하면 안 됩니다.
- 운영 환경에서는 먼저 `PERSONAS=home,preRide,health` 같은 **read 중심 조합**으로 시작하고, write는 정합성/정리 전략이 있을 때만 열어야 합니다.

## 11. 다음 확장 포인트

이 starter로 충분하지 않으면 다음 순서로 확장합니다.

1. featured / courses / detail / route-points를 각각 별도 scenario로 분리
2. read와 write 시나리오를 서로 다른 VU 비율로 분리
3. 인증/토큰 발급까지 포함한 auth burst 시나리오 추가
4. 결과를 CI에서 artifact로 남기는 backend workflow 추가

## 12. 결과 파일

- 기본 stdout 요약과 함께 `handleSummary()`가 JSON 요약을 저장합니다.
- 경로 기본값: `ops/loadtest/results/<TEST_ID>-summary.json`
- 이 파일을 기준으로 실행 창구, k6 결과, Grafana 해석을 한 보고서로 묶을 수 있습니다.
