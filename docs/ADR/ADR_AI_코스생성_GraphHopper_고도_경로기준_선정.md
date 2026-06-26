# ADR: AI 코스 생성에서 GraphHopper와 고도 점수를 경로 기준으로 사용한다

## 상태

Accepted, PR #36.

## 배경

기존 AI 코스 생성은 사용자의 텍스트 의도를 설명으로 바꾸는 데는 유용했지만, 실제 자전거 경로의 도로 등급, 자전거도로 여부, 노면, 고도 차이를 안정적으로 보장하지 못했다. 특히 사용자가 "평지 위주", "오르막 많은 코스", "강이 보이는 시원한 코스"처럼 주행 성격을 요청하면 백엔드는 설명이 아니라 실제 route point와 근거를 검증해야 한다.

서울대입구에서 부천역까지의 추천 경로와 서울대입구에서 안양천 합수부까지의 자유주행 보정 GPX 요구를 검토하면서, AI가 만든 좌표만 신뢰하면 다음 문제가 생겼다.

- 도로망을 따르지 않는 경로가 만들어질 수 있다.
- 고도 정보가 없거나 점수에 반영되지 않아 평지/업힐 선호를 구분하지 못한다.
- "남산은 국립극장 방면이 정석" 같은 라이딩 관습을 API 응답 근거로 설명하기 어렵다.
- Android/Web 클라이언트에 provider raw 응답이나 API key를 노출할 위험이 있다.

## 결정

AI worker는 텍스트 의도와 설명 후보를 만든다. 실제 경로 좌표와 도로 근거는 백엔드가 self-host GraphHopper를 호출해 만든다. 백엔드는 GraphHopper route point, road class, road environment, surface, smoothness, bike network, elevation summary를 API 응답의 source of truth로 사용한다.

최종 API 응답은 `airoute` BC가 조립한다.

- 텍스트 의도: `AiRouteTextIntentResolver`
- 경로 후보: `GraphHopperBicycleRoutingClient`
- 고도 요약: `ElevationSummary`
- 점수화: `RecommendationScoreCalculator`
- 응답 조립: `AiRoutePlanComposer`

## 검토한 선택지

### A. AI worker가 경로 좌표까지 직접 생성

장점:

- 구현이 가장 빠르다.
- 프롬프트만 바꾸면 다양한 설명을 만들기 쉽다.

단점:

- 실제 도로망, 자전거도로, 고도 근거가 약하다.
- 같은 요청의 결과 일관성이 낮다.
- 포트폴리오 관점에서 "검증 가능한 백엔드 경로 엔진"으로 설명하기 어렵다.

결론: 설명 후보 용도로만 유지한다.

### B. 외부 GraphHopper API만 사용

장점:

- 도로망 기반 경로와 elevation details를 빠르게 얻을 수 있다.
- 운영 초기에는 self-host보다 단순하다.

단점:

- API key와 호출 비용/제한에 의존한다.
- 테스트 compose와 AWS 부하테스트에서 독립적으로 재현하기 어렵다.
- provider 장애나 응답 포맷 변경이 백엔드 품질에 직접 영향을 준다.

결론: 장기 운영 옵션으로 남기되 이번 PR의 기준은 self-host로 둔다.

### C. Self-host GraphHopper + 백엔드 검수

장점:

- OSM 기반 경로, 도로 근거, 고도 정보를 테스트와 AWS에서 재현할 수 있다.
- Android/Web에는 정제된 DTO만 내려 provider raw 응답과 key를 숨길 수 있다.
- 경로 생성 품질을 테스트, evidence, k6 시나리오로 고정하기 좋다.

단점:

- OSM PBF, graph-cache, custom model, encoded values 관리가 필요하다.
- cold import와 메모리 사용량 때문에 compose/AWS runbook이 필요하다.

결론: 이번 PR의 선택이다.

### D. PostGIS만으로 자체 라우팅 구현

장점:

- 저장된 코스/주행 기록과 공간 쿼리를 한 DB에서 관리할 수 있다.
- 외부 라우팅 엔진 의존이 줄어든다.

단점:

- 자전거 도로망 라우팅, 고도, 도로 세부 속성을 직접 구현해야 한다.
- 현재 목표인 AI 코스 생성 보완보다 범위가 크다.

결론: 저장된 코스와 주행 기록의 공간 저장소로 유지한다.

## 구현 중 문제와 해결

- GraphHopper custom model 파일이 compose에 마운트되지 않아 runtime이 깨졌다. `ops/graphhopper` 디렉터리 전체를 읽기 전용으로 마운트하고 custom model 파일을 커밋했다.
- `bike.json` 이름이 GraphHopper 내장 모델과 충돌했다. 프로젝트 모델명을 `bike_project_bike.json`, `bike_project_elevation.json`로 바꿨다.
- custom model이 지원하지 않는 encoded value를 참조했다. `backward_bike_access`, `foot_road_access`, `roundabout`을 제거하고 config/model을 지원 값 기준으로 맞췄다.
- CI에서 `ProfileActivitySummaryIntegrationTest`가 UTC runner에서 실패했다. 서비스가 KST 기준 주간 집계를 쓰므로 테스트 fixture도 KST offset으로 고정했다.

## 결과와 여파

- AI route 응답은 `elevationSummary`, `recommendationScores.elevation`, `graphhopper.*` evidence를 포함한다.
- AI worker는 설명 후보 역할로 축소되고, route point source of truth는 GraphHopper 후보가 된다.
- self-host runtime 검증은 `docker-compose.test.yml`과 AWS compose+k6 evidence로 남긴다.
- 성능상 `course-follow` flow는 100 VU AWS 테스트에서 p95/p99 threshold를 넘었다. 이번 PR은 기능 검증과 병목 식별까지 완료한 상태이며, route-points/detail hot path 최적화는 [#37 course-follow hot path p95/p99 최적화](https://github.com/BIKE-PROJECT-MINJI/bike-back/issues/37)로 분리한다.

## 검증 근거

- Local/ngrok/browser evidence: `.omo/ulw-loop/evidence/G015-*`
- AI route HTTP evidence: `.omo/ulw-loop/evidence/G014-*`
- AWS compose/k6/cleanup evidence: `.omo/ulw-loop/evidence/G016-*`
- CI: PR #36 `bike-back-ci / test-and-build` success at `78e74aa`
