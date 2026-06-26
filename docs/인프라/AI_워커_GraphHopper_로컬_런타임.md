# AI 워커와 셀프호스트 GraphHopper 로컬 런타임

## 목적

경로 생성은 백엔드 `airoute` BC가 최종 API 응답을 만들고, 두 외부 의존성을 포트 뒤로 호출한다.

- `dev/bike-ai-route`: LangGraph/Gemini 기반 설명 worker
- `GraphHopper`: OSM 기반 자전거 경로 provider

백엔드는 provider raw 응답과 API key를 Android에 노출하지 않는다.

## 준비

GraphHopper는 OSM PBF와 graph-cache를 사용한다. 대용량 파일은 Git에 넣지 않는다.

```bash
cd dev/bike-back
sh ops/graphhopper/prepare-local.sh
```

첫 실행은 OSM import 때문에 오래 걸릴 수 있다. `ops/graphhopper/config-bike.yml`의 profile이나 encoded values를 바꾸면 graph-cache를 삭제하거나 위치를 바꿔 다시 import해야 한다.

## 실행

```bash
cd dev/bike-back
docker compose --profile routing -f docker-compose.local.yml up graphhopper ai-route-worker
```

host에서 `bootRun`으로 백엔드를 띄울 때:

```bash
AI_ROUTE_WORKER_BASE_URL=http://127.0.0.1:8091 \
GRAPHHOPPER_BASE_URL=http://127.0.0.1:8989 \
./gradlew bootRun --console=plain
```

백엔드를 compose network 안에서 띄우는 경우에는 다음 값을 사용한다.

```bash
AI_ROUTE_WORKER_BASE_URL=http://ai-route-worker:8091
GRAPHHOPPER_BASE_URL=http://graphhopper:8989
```

## 확인

```bash
curl -sS http://127.0.0.1:8091/health
curl -sS "http://127.0.0.1:8989/route?point=37.4812,126.9527&point=37.5404,127.0692&profile=bike&points_encoded=false&details=road_class&details=road_environment&details=surface&details=smoothness&details=bike_network"
```

백엔드 `POST /api/v1/ai-routes/plan` 응답에서는 `routePoints`가 2개 이상이고 `evidenceBadges[].source`에 `graphhopper.*`가 있으면 GraphHopper 경로가 반영된 것이다.

## 운영 원칙

- AI worker는 score와 evidence의 source of truth가 아니다. 설명 후보만 만든다.
- GraphHopper는 경로 후보 provider이고, PostGIS는 저장된 코스/주행 기록의 공간 저장소다.
- `GEMINI_API_KEY`, `GOOGLE_API_KEY`, `GRAPHHOPPER_API_KEY`는 코드/문서/evidence에 평문 저장하지 않는다.
- self-host GraphHopper는 key 없이 호출한다.
