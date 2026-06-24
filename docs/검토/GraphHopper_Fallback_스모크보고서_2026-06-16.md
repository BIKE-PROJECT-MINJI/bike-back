# GraphHopper fallback smoke report (2026-06-16)

## 작업 요약

GraphHopper 라우팅 설정에서 self-host endpoint가 실패할 때 configured hosted 대안으로 fallback되는지 실제 HTTP smoke로 확인했다. fake routing provider는 끄고, `routing.bicycle.provider=graphhopper` 조건에서 백엔드를 실행했다.

이번 검증은 외부 유료 GraphHopper hosted API를 호출한 검증이 아니다. 비용과 API key 노출을 피하기 위해 `GRAPHHOPPER_HOSTED_BASE_URL`에 GraphHopper 호환 mock endpoint를 연결해 "설정된 hosted 대안으로 fallback되는 코드 경로"를 검증했다.

## 사용한 환경/설정

- Backend: Spring Boot `bootRun`, `127.0.0.1:8080`
- Management port: `127.0.0.1:18081`
- DB: 임시 PostGIS 컨테이너 `127.0.0.1:55433`
- Redis: 임시 Redis 컨테이너 `127.0.0.1:6380`
- Weather: `fake`
- Address search: `fake`
- Routing provider: `graphhopper`
- Fake routing fallback: `false`
- Self-host GraphHopper URL: `http://127.0.0.1:59999`
- Hosted 대안 URL: `http://127.0.0.1:19191`
- Hosted 대안: GraphHopper 호환 local mock server

핵심 실행 환경 변수:

```bash
BICYCLE_ROUTING_PROVIDER=graphhopper
BICYCLE_ROUTING_FAKE_ENABLED=false
GRAPHHOPPER_BASE_URL=http://127.0.0.1:59999
GRAPHHOPPER_HOSTED_BASE_URL=http://127.0.0.1:19191
GRAPHHOPPER_RETRY_MAX_ATTEMPTS=1
```

## 장애 주입 방식

1. self-host URL은 닫힌 포트 `127.0.0.1:59999`로 지정해 connection refused를 유도했다.
2. hosted 대안 URL은 GraphHopper 호환 mock server `127.0.0.1:19191`로 지정했다.
3. 성공 시나리오에서는 mock `/route`가 GraphHopper 형식의 route를 반환했다.
4. 완전 실패 시나리오에서는 같은 mock `/route`가 HTTP 500을 반환하도록 바꿨다.
5. 성공 응답에는 `ascend`, `descend`, 3D elevation 좌표를 넣지 않아 elevation 없음 경고 분기를 확인했다.

## 실행한 검증 명령

Targeted test:

```bash
./gradlew --no-daemon test \
  --tests '*GraphHopperBicycleRoutingClientTest' \
  --tests '*BicycleRoutingServiceIntegrationTest' \
  --tests '*AiRoutePlanComposerTest' \
  --tests '*AiRoutePlannerServiceIntegrationTest' \
  --tests '*AiRouteControllerTest'
```

결과:

```text
BUILD SUCCESSFUL in 57s
```

HTTP smoke endpoint:

```bash
POST http://127.0.0.1:8080/api/v1/ai-routes/plan/from-text
X-Guest-Device-Id: gh-smoke-success-001
Content-Type: application/json

{
  "lat": 37.4812,
  "lon": 126.9527,
  "text": "평지 위주로 강이 보이는 코스 추천"
}
```

## 실제 HTTP smoke 결과

Self-host 실패 + hosted 대안 성공:

```text
health_status=200
success_status=200
route_point_count=3
ai_generated=false
hosted_mock_hits={"mode":1,"route":1}
```

Self-host 실패 + hosted 대안 실패:

```text
fail_status=400
fail_body={"code":400,"message":"GraphHopper 자전거 경로를 생성할 수 없습니다. self-host 또는 hosted GraphHopper 설정을 확인하세요.","data":null}
hosted_mock_hits={"mode":2,"route":2}
```

## routingMetadata 확인 결과

Self-host 실패 + hosted 대안 성공 응답의 `routingMetadata`:

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

확인 결과:

- `routingStatus=FALLBACK_USED`: 계약과 일치.
- `provider=GRAPHHOPPER`: fake provider가 아니라 GraphHopper client 경로.
- `fallbackUsed=true`: 계약과 일치.
- `fallbackReason`: self-host 실패 후 hosted 사용 사유가 드러남.
- `qualityStatus=VALID_WITH_WARNINGS`: 고도 정보 없음이 실패가 아니라 경고로 분류됨.
- `qualityMessage`: 계약 문구와 일치.

## 로그 핵심

Mock GraphHopper 로그:

```text
mock-route-hit count=1 mode=success query=point=37.4812,126.9527&point=37.543,126.902&profile=bike&points_encoded=false&elevation=true&locale=ko&details=road_class&details=road_environment&details=surface&details=smoothness&details=bike_network&details=average_slope&details=max_slope
mock-route-hit count=2 mode=fail query=point=37.4812,126.9527&point=37.543,126.902&profile=bike&points_encoded=false&elevation=true&locale=ko&details=road_class&details=road_environment&details=surface&details=smoothness&details=bike_network&details=average_slope&details=max_slope
```

Backend 로그:

```text
POST /api/v1/ai-routes/plan/from-text status=200
bad_request ... message=GraphHopper 자전거 경로를 생성할 수 없습니다. self-host 또는 hosted GraphHopper 설정을 확인하세요.
POST /api/v1/ai-routes/plan/from-text status=400
```

운영 메트릭 확인 제한:

- `http://127.0.0.1:18081/actuator/prometheus`는 smoke 환경에서 `401`을 반환했다.
- 따라서 이번 보고서의 핵심 evidence는 response metadata, hosted mock hit log, backend HTTP status log다.
- 운영 관측성을 강화하려면 GraphHopper fallback 시도/성공/실패 로그를 명시적으로 남기거나, 로컬 smoke용 OPS 인증 토큰 절차를 별도로 정리해야 한다.

## 수정 여부와 변경 파일

Product code 수정 없음.

문서 변경:

- `docs/graphhopper-fallback-smoke-report-2026-06-16.md`
- `/mnt/c/Users/alswl/Desktop/BIKE - 복사본/CONTEXT_LEDGER.md`
- `/mnt/c/Users/alswl/Desktop/BIKE - 복사본/SESSION_WORK_LOG.md`

## 테스트 결과

- GraphHopper client/service/composer/controller targeted test: 통과.
- 실제 HTTP smoke self-host 실패 + hosted 대안 성공: 통과.
- 실제 HTTP smoke self-host 실패 + hosted 대안 실패: 통과.
- 고도 정보 없음 `VALID_WITH_WARNINGS`: 통과.

## Go/No-Go 판단

Go:

- configured hosted 대안으로 fallback되는 백엔드 코드 경로는 통과.
- fallback 성공과 완전 실패가 응답상 구분된다.
- 고도 정보 없음은 실패가 아니라 `VALID_WITH_WARNINGS`로 분류된다.
- fake provider를 사용하지 않은 조건에서 검증했다.

No-Go 또는 과장 금지:

- 외부 유료 GraphHopper hosted API 자체를 실제 호출해 검증한 것은 아니다.
- EC2/운영 환경 장애 주입 검증은 아니다.
- GraphHopper self-host 서버의 실제 Docker 운영 장애 복구 검증은 아니다.
- Prometheus metric까지 검증했다고 쓰면 안 된다. smoke 환경에서는 401로 metric 조회가 막혔다.

## 포트폴리오에 써도 되는 근거

- "GraphHopper self-host endpoint 장애를 닫힌 포트로 주입하고, configured hosted 대안으로 fallback되는 백엔드 경로를 HTTP smoke로 검증했다."
- "AI 코스 응답의 `routingMetadata`에 `FALLBACK_USED`, `fallbackUsed=true`, fallback reason이 남도록 계약을 확인했다."
- "고도 정보가 없는 라우팅 응답은 실패가 아니라 `VALID_WITH_WARNINGS`로 분류해 사용자에게 품질 경고를 전달하도록 검증했다."
- "hosted 대안까지 실패하면 성공 응답처럼 포장하지 않고 400 오류와 GraphHopper 설정 확인 메시지를 반환함을 확인했다."

## 과장하면 안 되는 표현

- "실제 GraphHopper hosted API를 운영 key로 검증했다."
- "운영/EC2 환경에서 GraphHopper 장애 복구를 검증했다."
- "Prometheus metric 기반 fallback 관측성을 검증했다."
- "모든 라우팅 장애 시나리오를 검증했다."

## 남은 리스크

- hosted 대안은 GraphHopper 호환 local mock이므로, 실제 외부 hosted GraphHopper의 인증, quota, latency, 응답 차이는 별도 검증이 필요하다.
- backend 로그에는 GraphHopper self-host 실패와 hosted fallback 성공을 한 줄로 구분해주는 명시 로그가 부족하다.
- `/actuator/prometheus`는 smoke 환경에서 401이라 metric evidence를 직접 확인하지 못했다.
- 이번 검증은 로컬 단일 요청 smoke이며, 부하 상황에서 fallback이 몰릴 때의 latency/throughput 검증은 별도 성능 테스트 범위다.
