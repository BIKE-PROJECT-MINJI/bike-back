# 권한/JWT/beta 기반 구현 보고서

- 작성일: 2026-06-17
- 브랜치: `feature/auth-role-beta-foundation`
- 기준 커밋: `94e4254`
- 작업 범위: 커뮤니티/팟 구현 PR-1 권한/JWT/beta 기반

## 1. 목표

커뮤니티/팟 후속 기능의 전제인 권한 기반을 작게 구현한다. 이번 범위는 `USER`, `OPS_ADMIN` role 저장, JWT `roles` claim 발급, Spring Security 인가 판단, beta guard까지만 포함한다.

## 2. 변경 요약

- `user_roles` 테이블을 추가해 사용자 role을 저장할 수 있게 했다.
- `UserRole` enum을 추가하고 기본 role을 `USER`로 두었다.
- access token 발급 시 `roles` claim에 사용자 role 목록을 넣는다.
- `/api/v1/admin/**` 경로는 `ROLE_OPS_ADMIN` authority가 있어야 접근 가능하게 했다.
- 후속 기능에서 재사용할 `BetaAccessPolicy`를 추가했다.
- H2 테스트 스키마에 `user_roles`를 동기화했다.

## 3. 변경 파일

- `src/main/java/com/bikeprojectminji/bikeback/auth/entity/UserRole.java`
- `src/main/java/com/bikeprojectminji/bikeback/auth/entity/UserEntity.java`
- `src/main/java/com/bikeprojectminji/bikeback/auth/service/AuthTokenService.java`
- `src/main/java/com/bikeprojectminji/bikeback/global/config/SecurityConfig.java`
- `src/main/java/com/bikeprojectminji/bikeback/beta/service/BetaAccessPolicy.java`
- `src/main/resources/db/migration/V26__create_user_roles.sql`
- `src/test/resources/schema-h2.sql`
- `src/test/java/com/bikeprojectminji/bikeback/auth/service/AuthTokenServiceTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/auth/entity/UserRolePersistenceIntegrationTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/beta/service/BetaAccessPolicyTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/global/config/AdminEndpointSecurityTest.java`

## 4. 권한/JWT/beta 정책 결정

- 저장 role 값은 `USER`, `OPS_ADMIN`으로 시작한다.
- 기존 `ROLE_OPS`는 `/health/monitor` 보호용으로 유지하고, 커뮤니티 운영 권한은 `OPS_ADMIN`으로 분리한다.
- JWT `roles` claim에는 `USER`, `OPS_ADMIN`처럼 `ROLE_` prefix 없는 role 값을 넣는다.
- Spring Security는 기존 converter를 통해 `ROLE_USER`, `ROLE_OPS_ADMIN` authority로 변환한다.
- beta 권한은 기존 `users.beta_access_granted`를 기준으로 판단한다.
- beta 권한 부족은 `ForbiddenException("베타 초대 권한이 필요합니다.")`로 막는다.

## 5. API 계약 변경 여부

외부 API path, request DTO, response DTO는 변경하지 않았다.

단, 내부 인증 토큰의 access token claim에 `roles`가 추가된다. 기존 `tokenType`, `displayName`, `email` claim은 유지된다.

## 6. 검증

### RED

아래 테스트를 먼저 추가했고, 구현 전에는 `UserRole`, `BetaAccessPolicy` 부재로 컴파일 실패했다.

```bash
./gradlew --no-daemon test --tests 'com.bikeprojectminji.bikeback.auth.service.AuthTokenServiceTest' --tests 'com.bikeprojectminji.bikeback.beta.service.BetaAccessPolicyTest' --tests 'com.bikeprojectminji.bikeback.global.config.AdminEndpointSecurityTest' --tests 'com.bikeprojectminji.bikeback.auth.entity.UserRolePersistenceIntegrationTest'
```

### GREEN

같은 targeted test 재실행 결과 성공했다.

```bash
./gradlew --no-daemon test --tests 'com.bikeprojectminji.bikeback.auth.service.AuthTokenServiceTest' --tests 'com.bikeprojectminji.bikeback.beta.service.BetaAccessPolicyTest' --tests 'com.bikeprojectminji.bikeback.global.config.AdminEndpointSecurityTest' --tests 'com.bikeprojectminji.bikeback.auth.entity.UserRolePersistenceIntegrationTest'
```

결과: `BUILD SUCCESSFUL`

### 회귀 검증

```bash
./gradlew --no-daemon test --tests '*Auth*' --tests '*Security*' --tests '*Beta*' --tests '*MonitoringControllerTest'
```

결과: `BUILD SUCCESSFUL`

```bash
./gradlew --no-daemon test
```

결과: `BUILD SUCCESSFUL`

## 7. 남은 리스크

- `OPS_ADMIN`을 부여하는 운영 API나 관리자 화면은 아직 없다. 초기 부여는 DB seed, 운영 스크립트, 별도 관리자 API 중 후속 결정이 필요하다.
- 기존 사용자에게 `USER` role을 backfill하는 migration은 포함했지만, 운영 배포 전 Flyway checksum/기존 DB 상태 확인이 필요하다.
- beta 초대코드 해시 저장, 발급 주체, 기존 가입자 redeem API는 이번 범위가 아니다.
- Course publication, sourceDetached, moderation, public list, 팟 도메인은 구현하지 않았다.

## 8. 다음 PR 추천

다음 PR은 ADR 순서에 따라 `sourceDetached` 보강만 다루는 것이 좋다. `course_publications`나 moderation은 그 다음 PR로 분리한다.
