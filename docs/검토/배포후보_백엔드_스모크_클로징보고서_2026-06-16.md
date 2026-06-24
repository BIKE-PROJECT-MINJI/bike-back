# 배포 후보 백엔드 smoke 클로징 보고서 (2026-06-16)

## 최종 Go/No-Go

- **Go, 조건부**
- 근거: 로컬 임시 PostGIS/Redis와 배포 후보에 가까운 env override에서 health, auth register/JWT, AI route, GraphHopper fallback, ride summary/trace/delete, CORS, DB/Redis monitor, Prometheus 인증 접근까지 실제 HTTP smoke로 통과했다.
- 조건: 실제 배포에서는 아래 env를 placeholder/fake로 두면 안 된다. 특히 `AUTH_JWT_SECRET`, DB/Redis 접속 정보, `APP_CORS_ALLOWED_ORIGINS`, GraphHopper self-host/hosted 설정을 명시해야 한다.

## 사용한 브랜치/커밋/원격 상태

- Branch: `verify/deployment-candidate-smoke`
- Baseline commit: `7ddff17` (`main`, `origin/main`)
- 기존 untracked: `ARTIFACTS/`는 이번 작업 범위 밖이라 건드리지 않았다.

## 사용한 환경/프로필/env

로컬 임시 smoke 환경:

- Backend: `127.0.0.1:8080`
- Management: `127.0.0.1:18081`
- DB: 임시 PostGIS 컨테이너 `127.0.0.1:55433`
- Redis: 임시 Redis 컨테이너 `127.0.0.1:6380`
- Routing provider: `graphhopper`
- Fake routing: `false`
- GraphHopper self-host URL: 닫힌 포트 `http://127.0.0.1:59999`
- GraphHopper hosted 대안 URL: GraphHopper 호환 local mock `http://127.0.0.1:19191`
- Weather provider: `fake`
- Address search provider: `fake`
- CORS allowed origins:
  - `http://127.0.0.1:8081`
  - `http://localhost:8081`
  - `https://rank-nonapplicative-fluidly.ngrok-free.dev`

이번 smoke에서 실제 값을 보고서에 쓰지 않은 민감 env:

- `AUTH_JWT_SECRET`
- DB password
- GraphHopper API key
- JWT access/refresh token

## 배포 후보 env 체크

배포 시 반드시 명시해야 하는 값:

| Env | 판단 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | 필수. 운영 DB URL 필요 |
| `SPRING_DATASOURCE_USERNAME` | 필수 |
| `SPRING_DATASOURCE_PASSWORD` | 필수. 보고서/로그에 값 노출 금지 |
| `SPRING_DATA_REDIS_URL` | 필수. Redis 연결 필요 |
| `AUTH_JWT_SECRET` | 필수. 32바이트 이상, placeholder 금지 |
| `AUTH_JWT_ISSUER` | 배포 환경별 고정 권장 |
| `APP_CORS_ALLOWED_ORIGINS` | 필수. 실제 프론트/ngrok/운영 origin을 명시해야 함 |
| `BICYCLE_ROUTING_PROVIDER` | `graphhopper` 권장 |
| `BICYCLE_ROUTING_FAKE_ENABLED` | 배포 후보에서는 `false` |
| `GRAPHHOPPER_BASE_URL` | self-host GraphHopper URL 필요 |
| `GRAPHHOPPER_HOSTED_BASE_URL` | hosted fallback 실사용 시 필요 |
| `GRAPHHOPPER_API_KEY` | hosted fallback 실사용 시 필요. 값 노출 금지 |
| `WEATHER_PROVIDER` | 실제 날씨 검증/운영에서는 `open-meteo`; 이번 smoke는 외부 의존성을 줄이기 위해 `fake` |
| `ADDRESS_SEARCH_PROVIDER` | 실제 주소 검색 운영에서는 외부 provider/key 필요. 이번 smoke는 `fake` |

현재 `.env` 안전 점검 결과:

- `.env` 파일은 존재한다.
- `AUTH_JWT_SECRET`은 placeholder 상태로 감지됐다. 이 상태로 배포하면 안 된다.
- `APP_CORS_ALLOWED_ORIGINS`는 `.env`에 없어서 기본값만 적용된다. ngrok 또는 운영 프론트 origin은 별도로 넣어야 한다.
- `GRAPHHOPPER_API_KEY`는 값이 있는 상태로 감지됐지만, 값 자체는 읽거나 보고하지 않았다.
- `GRAPHHOPPER_HOSTED_BASE_URL`은 `.env`에 명시돼 있지 않았다.

## 실행한 smoke 명령

Targeted test:

```bash
./gradlew --no-daemon test \
  --tests '*GraphHopperBicycleRoutingClientTest' \
  --tests '*BicycleRoutingServiceIntegrationTest' \
  --tests '*AiRoutePlanComposerTest' \
  --tests '*AiRoutePlannerServiceIntegrationTest' \
  --tests '*AiRouteControllerTest' \
  --tests '*RideRecordDeletionIntegrationTest' \
  --tests '*RideRecordControllerTest' \
  --tests '*AuthControllerTest' \
  --tests '*MonitoringControllerTest' \
  --console=plain
```

결과:

```text
BUILD SUCCESSFUL in 3m 16s
```

재사용 smoke script:

```bash
AUTH_JWT_SECRET=<masked> \
AUTH_JWT_ISSUER=bike-back-deploy-smoke \
BIKE_SMOKE_GRAPHHOPPER_CONTROL_URL=http://127.0.0.1:19191 \
BIKE_SMOKE_CORS_ORIGINS=http://127.0.0.1:8081,http://localhost:8081,https://rank-nonapplicative-fluidly.ngrok-free.dev \
BIKE_SMOKE_OUTPUT=/tmp/bike-deploy-smoke/evidence-script-2.json \
ops/smoke/deployment_candidate_smoke.py
```

결과 요약:

```json
{
  "health": 200,
  "monitorOps": 200,
  "register": 200,
  "aiSuccess": 200,
  "aiFail": 400,
  "summary": 200,
  "trace": 200,
  "delete": {
    "status": 204,
    "bodyBytes": 0,
    "body": null
  },
  "dbCounts": {
    "flywaySuccessCount": "25",
    "ride_records": "0",
    "ride_record_points": "0",
    "ride_record_processed_points": "0"
  }
}
```

## Health/Auth 결과

- `GET /health`: `200`
- 응답: `status=ok`, `service=bike-back`
- `GET /health/monitor` without JWT: `401`
- smoke용 OPS JWT 사용 시 `GET /health/monitor`: `200`
- monitor 응답:
  - database: `ok`, `select 1 success`
  - redis: `ok`, `PONG`
- `POST /api/v1/auth/register`: `200`
- register 응답에서 access token 발급 확인. 토큰 값은 기록하지 않았다.

## Ride summary/trace/delete 결과

- `POST /api/v1/ride-records/summary`: `200`
  - `rideRecordId` 반환
  - `routePointCount=0`
  - `finalizationStatus=FINALIZING`
- `POST /api/v1/ride-records/{rideRecordId}/trace`: `200`
  - `routePointCount=2`
  - `finalizationStatus=FINALIZING`
- async finalization 로그:
  - `ride record finalization ready rideRecordId=... processedPointCount=2`
- `DELETE /api/v1/ride-records/{rideRecordId}`: `204`
  - body `0 byte`
  - body `null`
- 삭제 후 DB:
  - `ride_records=0`
  - `ride_record_points=0`
  - `ride_record_processed_points=0`

## AI route/fallback 결과

### self-host 실패 + hosted 대안 성공

- Endpoint: `POST /api/v1/ai-routes/plan/from-text`
- HTTP status: `200`
- `routePointCount=3`
- `aiGenerated=false`
- routing metadata:

```json
{
  "routingStatus": "FALLBACK_USED",
  "provider": "GRAPHHOPPER",
  "fallbackUsed": true,
  "fallbackReason": "self-host 실패 후 hosted GraphHopper 사용",
  "qualityStatus": "VALID_WITH_WARNINGS",
  "qualityMessage": "고도 정보가 없어 평지/오르막 선호 검증 정확도가 낮습니다."
}
```

### self-host 실패 + hosted 대안 실패

- HTTP status: `400`
- 응답 메시지:
  - `GraphHopper 자전거 경로를 생성할 수 없습니다. self-host 또는 hosted GraphHopper 설정을 확인하세요.`
- 성공 응답처럼 포장되지 않았다.

### GraphHopper 호출 범위 구분

- 검증됨:
  - `routing.bicycle.provider=graphhopper`
  - `BICYCLE_ROUTING_FAKE_ENABLED=false`
  - self-host 닫힌 포트 장애
  - GraphHopper 호환 local mock hosted 대안 fallback
  - hosted 대안 실패 시 400 오류
  - elevation 없음 -> `VALID_WITH_WARNINGS`
- 미검증:
  - 실제 외부 hosted GraphHopper API 호출
  - 외부 hosted GraphHopper quota
  - 외부 hosted GraphHopper latency

외부 hosted 실호출 보류 이유:

- `.env`에 API key 존재는 감지됐지만 값은 보지 않았다.
- `GRAPHHOPPER_HOSTED_BASE_URL`이 명시돼 있지 않았다.
- 사용자가 비용을 줄이고 싶다고 명시한 기존 맥락이 있어, 쿼터/비용 확인 없이 외부 유료 API 호출은 하지 않았다.

## CORS 결과

Preflight endpoint:

```text
OPTIONS /api/v1/ai-routes/plan/from-text
Access-Control-Request-Method: POST
Access-Control-Request-Headers: content-type,x-guest-device-id
```

확인 결과:

| Origin | Status | 판단 |
| --- | ---: | --- |
| `http://127.0.0.1:8081` | 200 | 허용 |
| `http://localhost:8081` | 200 | 허용 |
| `https://rank-nonapplicative-fluidly.ngrok-free.dev` | 200 | smoke env override에서 허용 |
| `https://not-allowed.example.com` | 403 | 미허용 정상 차단 |

주의:

- 기본 `application.yml`에는 ngrok origin이 없다.
- 프론트 ngrok 시연을 계속하려면 `APP_CORS_ALLOWED_ORIGINS`에 해당 ngrok origin을 반드시 포함해야 한다.

## DB/Redis/migration 결과

- Flyway validation: `25 migrations`
- 깨끗한 PostGIS에서 v25까지 적용 성공
- DB monitor: `select 1 success`
- Redis monitor: `PONG`
- 직접 Redis ping: `PONG`

## 로그/관측성 확인 결과

Backend 로그 핵심:

```text
GET /health status=200
GET /health/monitor status=200
POST /api/v1/auth/register status=200
POST /api/v1/ai-routes/plan/from-text status=200
POST /api/v1/ai-routes/plan/from-text status=400
POST /api/v1/ride-records/summary status=200
POST /api/v1/ride-records/{id}/trace status=200
ride record finalization ready ... processedPointCount=2
DELETE /api/v1/ride-records/{id} status=204
```

GraphHopper 호환 mock 로그:

```text
mock-route-hit count=... mode=success ... /route ...
mock-route-hit count=... mode=fail ... /route ...
```

Actuator/Prometheus:

- 무인증 `/actuator/health`: `401`
- OPS JWT `/actuator/health`: `200`, `{"status":"UP"}`
- 무인증 `/actuator/prometheus`: `401`
- OPS JWT `/actuator/prometheus`: `200`
- routing failure metric 확인:
  - `bike_routing_provider_failure_total{provider="graphhopper",reason="rest_client_exception"}`
  - `bike_routing_provider_failure_total{provider="graphhopper",reason="http_5xx"}`

## 수정 여부와 변경 파일

Product code 수정 없음.

추가/수정 파일:

- `ops/smoke/deployment_candidate_smoke.py`
- `docs/deployment-candidate-smoke-report-2026-06-16.md`
- `/mnt/c/Users/alswl/Desktop/BIKE - 복사본/CONTEXT_LEDGER.md`
- `/mnt/c/Users/alswl/Desktop/BIKE - 복사본/SESSION_WORK_LOG.md`

## 테스트 결과

- Targeted backend test: 통과
- 전체 backend test:
  - `./gradlew --no-daemon test --console=plain`
  - `BUILD SUCCESSFUL in 6m 53s`
- Smoke script syntax check:
  - `python3 -m py_compile ops/smoke/deployment_candidate_smoke.py`: 통과
- Smoke script execution: 통과
- 실제 HTTP smoke: 통과

## Blocking issue

없음. 단, 아래 조건을 지켜야 Go다.

- 실제 배포 env에서 `AUTH_JWT_SECRET` placeholder 제거
- 실제 배포 env에서 DB/Redis 접속 정보 명시
- 실제 프론트 origin을 `APP_CORS_ALLOWED_ORIGINS`에 명시
- GraphHopper hosted fallback을 운영에서 주장하려면 `GRAPHHOPPER_HOSTED_BASE_URL`과 API key를 별도 검증

## Non-blocking improvement

- GraphHopper fallback 성공/실패를 backend application log에 더 직접적인 structured log로 남기면 운영 분석이 쉬워진다.
- 실제 외부 hosted GraphHopper 1회 smoke는 비용/쿼터 확인 후 별도 작업으로 진행한다.
- AI worker/Gemini까지 포함한 end-to-end AI generated 경로는 이번 smoke에서 미검증이다.
- `/actuator/prometheus`는 현재 인증 필요 정책이 맞지만, Prometheus가 안전하게 scrape할 인증 방식은 운영 배포 전에 별도 확정이 필요하다.

## 포트폴리오에 써도 되는 근거

- 깨끗한 PostGIS/Redis 환경에서 Flyway v25 migration, health, auth, AI route, ride record 저장/삭제, CORS를 한 번의 배포 후보 smoke로 검증했다.
- GraphHopper self-host 장애를 닫힌 포트로 주입하고, configured hosted 대안 fallback 경로와 metadata를 확인했다.
- 자유주행 기록은 summary -> trace -> delete 흐름에서 `204 No Content`, body 0 byte, DB record/point 0건을 실제 HTTP/DB로 확인했다.
- CORS는 local dev origin과 ngrok origin 허용, 미허용 origin 차단을 preflight로 확인했다.

## 과장하면 안 되는 표현

- 실제 외부 hosted GraphHopper API까지 검증 완료
- Gemini/외부 AI worker까지 실제 생성 검증 완료
- 운영 DB/운영 Redis에서 검증 완료
- EC2/운영 배포 환경에서 검증 완료
- Prometheus 운영 scrape 구성이 완료됨

## 다음에 프론트가 받아야 할 값

- Backend base URL:
  - 로컬 smoke: `http://127.0.0.1:8080`
  - ngrok/운영 URL은 배포 후 별도 전달
- Allowed origin:
  - `http://127.0.0.1:8081`
  - `http://localhost:8081`
  - 현재 ngrok 시연: `https://rank-nonapplicative-fluidly.ngrok-free.dev`
- Auth/token 주의사항:
  - register/login 응답의 `accessToken`은 `Authorization: Bearer <token>`으로만 사용
  - 토큰 값을 로그/문서/화면에 표시하지 않음
  - DELETE 성공은 JSON 파싱하지 않고 `204`, body 0 byte로 처리
- AI route metadata 표시 주의사항:
  - `routingStatus=FALLBACK_USED`면 fallback 상태를 표시
  - `qualityStatus=VALID_WITH_WARNINGS`면 경고 문구를 표시
  - `provider=GRAPHHOPPER`는 fake routing이 아니라 GraphHopper 경로 기준임을 의미
  - `aiGenerated=false`인 경우 외부 AI worker 생성이 아니라 백엔드 deterministic plan임을 과장하지 않음

## CONTEXT_LEDGER.md 기록 여부

- 기록 완료: `CONTEXT_LEDGER.md`와 `SESSION_WORK_LOG.md`에 배포 후보 smoke 결과를 요약했다.
