# AI 코스 생성 기능 리뷰

## 비개발자용 요약

AI 코스 생성은 사용자의 현재 위치, 목적지 또는 텍스트 요청을 받아 자전거 경로 후보를 만들고, 날씨/고도/도로 근거를 붙여 추천 이유를 설명하는 기능이다.

현재 구조는 다음 순서로 동작한다.

1. 사용자가 REST 또는 WebSocket으로 요청한다.
2. 서버가 로그인 사용자 quota를 확인한다.
3. 텍스트 요청이면 내부 규칙으로 목적지/선호도를 먼저 해석한다.
4. GraphHopper 자전거 경로를 생성한다.
5. 경로, 날씨, 고도, 도로 근거를 점수화한다.
6. AI worker가 가능하면 문장과 추천 설명을 보강한다.
7. AI worker가 실패하면 서버가 만든 기본 경로 응답을 반환한다.

이번 course-follow 성능 PR에서 AI 코스 생성은 최종 성능 개선 범위에 포함하지 않았다. 실제 Gemini/GraphHopper key를 넣은 smoke에서 `from-text`가 안정적으로 성공하지 못했기 때문이다. 따라서 AI 코스 생성은 "후속 안정화 대상"으로 남겼다.

## 현재 API

- REST 기본 요청: `POST /api/v1/ai-routes/plan`
- REST 텍스트 요청: `POST /api/v1/ai-routes/plan/from-text`
- WebSocket 요청: `/ws/v1/ai-routes`

관련 코드:

- `src/main/java/com/bikeprojectminji/bikeback/airoute/controller/AiRouteController.java`
- `src/main/java/com/bikeprojectminji/bikeback/airoute/service/AiRoutePlannerService.java`
- `src/main/java/com/bikeprojectminji/bikeback/airoute/service/AiRouteTextIntentResolver.java`
- `src/main/java/com/bikeprojectminji/bikeback/airoute/service/HttpAiRouteWorkerClient.java`
- `src/main/java/com/bikeprojectminji/bikeback/routing/infrastructure/GraphHopperBicycleRoutingClient.java`
- `src/main/java/com/bikeprojectminji/bikeback/airoute/websocket/AiRouteWebSocketHandler.java`

## 왜 지금 구조로 만들었나

AI에게 처음부터 모든 결정을 맡기면 결과가 흔들린다. 그래서 서버가 먼저 자전거 경로의 뼈대를 만든다.

- GraphHopper는 실제 도로 네트워크와 고도 정보를 제공한다.
- 서버는 거리, 경사, 풍경/자전거길 근거를 점수화한다.
- AI worker는 이 결과를 사람이 읽기 좋은 설명으로 보강한다.

즉, 현재 방향은 "AI가 경로를 상상해서 만드는 방식"이 아니라 "서버가 검증 가능한 경로를 만들고 AI가 설명을 돕는 방식"이다. 포트폴리오 관점에서도 이쪽이 더 설득력 있다.

## 현재 알고리즘 구조

### 텍스트 해석

`AiRouteTextIntentResolver`는 현재 간단한 키워드 규칙이다.

- "오르막", "업힐", "산", "남산" → 남산 N서울타워, `CLIMB_FIRST`
- "평지", "완만", "편한" → 안양천합수부, `FLAT_FIRST`
- "강", "한강", "시원", "풍경" → 반포한강공원, `BALANCED_ELEVATION`
- 그 외 → 현재 위치 기준 기본 추천

장점:

- 결과가 예측 가능하다.
- 테스트하기 쉽다.
- AI 실패와 무관하게 기본 응답을 만들 수 있다.

한계:

- 사용자의 자유 문장을 깊게 이해하지 못한다.
- 목적지 후보가 하드코딩되어 있다.
- "서울대입구에서 40km, 업힐 2개, 복귀 루프" 같은 복합 요구를 잘 처리하기 어렵다.

### GraphHopper 경로 생성

`GraphHopperBicycleRoutingClient`는 `/route`를 호출하며 다음 정보를 요청한다.

- `profile=bike`
- `points_encoded=false`
- `elevation=true`
- `road_class`, `road_environment`, `surface`, `smoothness`, `bike_network`
- `average_slope`, `max_slope`

이 정보는 추천 점수와 고도 요약에 쓰인다.

### AI worker 보강

`HttpAiRouteWorkerClient`는 `fallbackPlan`까지 포함해 AI worker에 보낸다. worker가 실패하면 `Optional.empty()`로 내려가고, 서버는 기본 plan을 그대로 반환한다.

장점:

- AI 서버 장애가 전체 기능 장애로 바로 번지지 않는다.
- 서버 기본 응답으로 최소 기능을 유지한다.

한계:

- 실패 원인이 응답에 구체적으로 남지 않는다.
- 현재 성능 gate에서 AI route는 안정적으로 통과하지 못했다.

## 추가 개선 방향

1. 텍스트 해석을 규칙 + 구조화 LLM 결과로 분리
   - AI가 바로 경로를 만들지 말고, 먼저 `distanceRange`, `elevationPreference`, `sceneryPreference`, `avoidance`, `destinationHint` 같은 구조화 JSON만 만들게 한다.
   - 서버는 이 JSON을 검증한 뒤 GraphHopper 후보를 생성한다.

2. 후보 경로를 1개가 아니라 여러 개 생성
   - 같은 출발지에서 `flat`, `climb`, `river`, `canonical` 후보를 각각 만든다.
   - 점수와 이유가 다른 3~5개 후보를 사용자에게 보여준다.

3. 고도 점수를 더 명확히 분리
   - `totalAscentM`, `maxSlopePercent`, `averageSlopePercent`를 별도 점수로 둔다.
   - "평지 원함"이면 상승고도 감점, "업힐 원함"이면 상승고도 가점처럼 정책화한다.

4. 정석 코스 사전 구축
   - 남산 국립극장 방향, 한강/안양천 합수부 같은 정석 코스를 DB seed 또는 별도 table로 관리한다.
   - AI가 "남산"을 말하면 정석 코스 anchor를 먼저 고른 뒤 GraphHopper로 보정한다.

5. AI route 전용 성능 gate 분리
   - course/free 100VU와 섞지 말고 AI route만 별도 k6 시나리오로 측정한다.
   - GraphHopper warm-up, AI worker timeout, 실패율, p95/p99를 따로 관리한다.

## 리스크

- WebSocket은 인증은 걸려 있지만 allowed origin이 `*`다. 실제 배포에서는 프론트 도메인으로 제한하는 것이 좋다.
- `from-text`는 아직 최종 안정화 증거가 없다.
- GraphHopper CPU가 높아 AI route p99를 끌어올릴 수 있다.
- 텍스트 해석이 현재 하드코딩 규칙이라 확장성이 낮다.
