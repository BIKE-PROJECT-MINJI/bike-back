# bike-back

자전거 여행용 주행 HUD 앱의 백엔드 저장소입니다.

이 저장소는 **코스 조회 / 주행 정책 / 날씨 / 2차 인증·기록·코스 저장·공유** 기준을 담당합니다.

## 현재 기술 스택

- Java 17
- Spring Boot 3.5.7
- Gradle Wrapper
- Spring Web
- Spring Security
- OAuth2 Resource Server (JWT 검증/발급)
- Spring Data JPA
- Spring Data Redis
- Flyway
- PostgreSQL

## 현재 구현 범위

### 1차 핵심 범위
- 추천 코스 조회
- 전체 코스 목록 조회
- 코스 상세 조회
- 코스 경로 좌표 조회
- ride 시작 가능 여부 평가
- 현재 날씨 조회

### 2차 백엔드 범위
- 회원가입 + 로그인 + JWT 발급
- 내 인증 상태 조회 (`/api/v1/auth/me`)
- 내 프로필 조회/수정 (`/api/v1/profile/me`)
- 자유 주행 기록 저장 (`/api/v1/ride-records`)
- 기록 기반 코스 생성 (`POST /api/v1/courses`)
- 코스 수정 / 공개 범위 변경
- 공개 코스 검색 (`/api/v1/courses/search`)
- 코스 공유 정보 조회 (`/api/v1/courses/{id}/share`)
- 코스 다운로드 (`/api/v1/courses/{id}/download`)
- `PUBLIC / UNLISTED / PRIVATE` 접근 제어

## 주요 API 묶음

- 인증/프로필
  - `POST /api/v1/auth/register`
  - `POST /api/v1/auth/login`
  - `GET /api/v1/auth/me`
  - `GET /api/v1/profile/me`
  - `PATCH /api/v1/profile/me`
- 코스
  - `GET /api/v1/courses`
  - `GET /api/v1/courses/search`
  - `GET /api/v1/courses/featured`
  - `GET /api/v1/courses/{courseId}`
  - `GET /api/v1/courses/{courseId}/route-points`
  - `POST /api/v1/courses`
  - `PUT /api/v1/courses/{courseId}`
  - `PATCH /api/v1/courses/{courseId}/visibility`
  - `POST /api/v1/courses/{courseId}/share`
  - `GET /api/v1/courses/{courseId}/download`
  - `POST /api/v1/courses/{courseId}/ride-policy/evaluate`
- 주행 기록
  - `POST /api/v1/ride-records`
- 날씨
  - `GET /api/v1/weather/current`

## 패키지 구조

- `controller/` : HTTP 엔드포인트
- `service/` : 비즈니스 로직 + 트랜잭션
- `repository/` : 영속성, 커스텀 조회
- `entity/` : JPA 엔티티
- `dto/` : 요청/응답 DTO
- `global/` : 공통 설정, 예외, 응답 래퍼

## 실행 / 검증

Windows PowerShell 기준:

- 전체 테스트: `./gradlew.bat test`
- 전체 빌드: `./gradlew.bat build`
- 앱 실행(dev): `./gradlew.bat bootRun`

## 현재 문서 기준

이 저장소 구현은 아래 current 문서를 따른다.

- `DOCS/00_기준/프로젝트_헌법.md`
- `DOCS/00_기준/통합_개발_테스트_방법론.md`
- `DOCS/15_기능명세/backend/인증_프로필_백엔드_계약_및_요구사항.md`
- `DOCS/15_기능명세/backend/코스_생성_공유_백엔드_계약_및_요구사항.md`

## 유지 원칙

- 기능이 추가되거나 제거되면 **README의 “현재 구현 범위”와 “주요 API 묶음”을 함께 갱신**합니다.
- 기술 스택이 바뀌면 **“현재 기술 스택”을 바로 갱신**합니다.
- README는 계획이 아니라 **지금 실제로 구현되어 있는 범위만** 적습니다.
