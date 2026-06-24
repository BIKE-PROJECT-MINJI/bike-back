# Backend Release Hardening R3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 계정 삭제, AI route 인증/쿼터, event 민감정보 제거, 코스 접근 정책, provider 실패 metric을 출시 직전 기준으로 잠근다.

**Architecture:** 삭제 정책은 `AuthService`가 인증 aggregate를 닫고 별도 계정 데이터 정리 서비스가 course/ride/event 소유 데이터를 정리한다. 코스 접근 판단은 `CourseAccessPolicy`로 모아 `CourseService`와 `RidePolicyService`가 같은 도메인 규칙을 쓰게 한다. AI route는 REST와 WebSocket 모두 인증 subject를 quota 단위로 쓰고, provider 실패는 adapter에서 reason_code metric으로 남긴다.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring Security OAuth2 Resource Server, Spring Data JPA, Micrometer, JUnit 5, AssertJ, Mockito.

---

### Task 1: 정책 문서 잠금

**Files:**
- Modify: `../../DOCS/00_기준/10_개인정보_보안/개인정보_위치정보_운영정책.md`
- Modify: `../../DOCS/15_기능명세/backend/백엔드_API_명세_통합표.md`
- Modify: `../../DOCS/15_기능명세/backend/API_계약_테스트_명세.md`

- [x] **Step 1: 정책 계약을 먼저 문서화**

계정 삭제는 auth link/consent/session 제거, raw ride와 point 삭제, private/unlisted course 삭제, public course 익명화를 수행한다.

AI route는 로그인 사용자만 호출 가능하며 REST와 WebSocket은 같은 user subject 기준 quota를 공유한다.

event properties는 token류뿐 아니라 raw 좌표, 주소, 검색어, route trace, polyline을 저장하지 않는다.

provider failure metric은 `provider`, `reason` tag를 필수로 남긴다.

### Task 2: RED 테스트 작성

**Files:**
- Modify: `src/test/java/com/bikeprojectminji/bikeback/auth/service/KakaoAuthServiceIntegrationTest.java`
- Modify: `src/test/java/com/bikeprojectminji/bikeback/event/service/ClientEventServiceTest.java`
- Modify: `src/test/java/com/bikeprojectminji/bikeback/global/metrics/BikeMetricsRecorderTest.java`
- Modify: `src/test/java/com/bikeprojectminji/bikeback/routing/infrastructure/GraphHopperBicycleRoutingClientTest.java`
- Create: `src/test/java/com/bikeprojectminji/bikeback/airoute/service/AiRouteQuotaServiceTest.java`
- Create: `src/test/java/com/bikeprojectminji/bikeback/course/service/CourseAccessPolicyTest.java`

- [x] **Step 1: 실패 테스트 작성**

테스트는 현재 구현에 없는 메서드/정책을 기대하므로 최초 실행에서 compile 또는 assertion 실패해야 한다.

### Task 3: GREEN 구현

**Files:**
- Create: `src/main/java/com/bikeprojectminji/bikeback/auth/service/AccountDeletionService.java`
- Create: `src/main/java/com/bikeprojectminji/bikeback/airoute/service/AiRouteQuotaService.java`
- Create: `src/main/java/com/bikeprojectminji/bikeback/global/exception/TooManyRequestsException.java`
- Create: `src/main/java/com/bikeprojectminji/bikeback/course/service/CourseAccessPolicy.java`
- Modify: `src/main/java/com/bikeprojectminji/bikeback/auth/service/AuthService.java`
- Modify: `src/main/java/com/bikeprojectminji/bikeback/airoute/controller/AiRouteController.java`
- Modify: `src/main/java/com/bikeprojectminji/bikeback/airoute/websocket/AiRouteWebSocketHandler.java`
- Modify: `src/main/java/com/bikeprojectminji/bikeback/global/config/SecurityConfig.java`
- Modify: `src/main/java/com/bikeprojectminji/bikeback/global/exception/GlobalExceptionHandler.java`
- Modify: repositories under course/ride/event.
- Modify: routing provider adapters and `BikeMetricsRecorder`.

- [x] **Step 1: 테스트를 통과하는 최소 구현**

기존 API/DTO는 유지하고, 인증 정책 변경으로 깨지는 smoke는 인증 token을 실어 호출하도록 갱신한다.

### Task 4: 검증

**Files:**
- Create: `../../보고서/2026-05-27_T-backend-release-hardening-r3_수정결과보고서.md`

- [x] **Step 1: targeted test**

Run: `./gradlew test --tests '*KakaoAuthServiceIntegrationTest' --tests '*ClientEventServiceTest' --tests '*AiRouteQuotaServiceTest' --tests '*CourseAccessPolicyTest' --tests '*BikeMetricsRecorderTest' --tests '*GraphHopperBicycleRoutingClientTest'`

- [x] **Step 2: full backend test**

Run: `./gradlew test`
