# bike-back

`bike-back`은 GAJA 프로젝트의 Spring Boot API 서버입니다.

GAJA는 **가벼운 자전거 여행을 위한 주행 HUD 프로젝트**이고,
이 저장소는 그중 코스, 정책, 날씨, 인증/프로필, 주행 기록, 이벤트 수집 같은 backend 책임을 맡습니다.

> Organization: [BIKE-PROJECT-MINJI](https://github.com/BIKE-PROJECT-MINJI)  
> Related repositories: [bike-front](https://github.com/BIKE-PROJECT-MINJI/bike-front)

## What this repository does

GAJA는 자전거 여행 중 **경로와 상태 정보를 한 화면에서 확인하게 해 외부 앱 재진입을 줄이는 주행 HUD 앱**입니다.

`bike-back`은 아래 책임을 가집니다.
- 홈 추천 코스 / 전체 코스 목록 / 코스 상세 / 경로 좌표 제공
- ride 시작 가능 여부 및 이탈 경고 계산
- 날씨 / 풍향 / 풍속 조회와 fallback 정책 제공
- 최소 인증 / 프로필 / owner 판정
- 자유 주행 기록 저장과 기록 기반 코스 생성
- 클라이언트 행동 이벤트 수집과 ride telemetry 저장
- 코스 공개 범위 / 공유 / 다운로드 제어

## Current scope

### 현재 구현/연결 범위
- 추천 코스 조회
- 전체 코스 목록 조회
- 코스 상세 조회
- 코스 경로 좌표 조회
- ride 시작 가능 여부 평가
- 현재 날씨 조회
- 최근 위치 조회
- health endpoint

### 다음 단계에서 확장할 범위
- 회원가입 / 로그인 / JWT 발급
- 내 인증 상태 조회
- 내 프로필 조회 / 수정
- 자유 주행 기록 저장
- 자유 주행 finalization 상태 조회 / 재생성
- 기록 기반 코스 생성
- 코스 수정 / 공개 범위 변경
- 공개 코스 검색
- 코스 공유 / 다운로드
- 이벤트 수집 API
- `PUBLIC / UNLISTED / PRIVATE` 접근 제어

## Core API surface

| Domain | Endpoints |
|---|---|
| Auth / Profile | `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me`, `GET/PATCH /api/v1/profile/me` |
| Course Discovery | `GET /api/v1/courses`, `GET /api/v1/courses/featured`, `GET /api/v1/courses/{courseId}`, `GET /api/v1/courses/{courseId}/route-points` |
| Course Create / Share | `POST /api/v1/courses`, `PUT /api/v1/courses/{courseId}`, `PATCH /api/v1/courses/{courseId}/visibility`, `POST /api/v1/courses/{courseId}/share`, `GET /api/v1/courses/search`, `GET /api/v1/courses/{courseId}/download` |
| Ride | `POST /api/v1/courses/{courseId}/ride-policy/evaluate`, `POST /api/v1/ride-records`, `GET /api/v1/ride-records/{rideRecordId}`, `POST /api/v1/ride-records/{rideRecordId}/regenerate` |
| Event | `POST /api/v1/events`, `POST /api/v1/events/batch` |
| Weather / Ops | `GET /api/v1/weather/current`, `GET /health` |

## Stack

- Java 17
- Spring Boot 3.5.7
- Spring Web
- Spring Security / JWT
- Spring Data JPA
- Spring Data Redis
- Flyway
- PostgreSQL + PostGIS
- Gradle Wrapper

## Project structure

```text
com.bikeprojectminji.bikeback
├─ auth/
├─ course/
├─ event/
├─ location/
├─ profile/
├─ ride/
├─ weather/
└─ global/
```

- 도메인 기준 최상단 패키지 구조를 사용합니다.
- 각 도메인 아래에는 `controller / service / repository / entity / dto / infrastructure` 중 필요한 레이어만 둡니다.
- `global/`은 공통 설정, 예외, 응답, Redis, health 같은 기술 레이어만 둡니다.

## Local development

### WSL / bash
```bash
./gradlew test
./gradlew build
./gradlew bootRun
```

### Windows PowerShell
```powershell
./gradlew.bat test
./gradlew.bat build
./gradlew.bat bootRun
```

## Verification policy

- 기능 추가/수정 시 검증 테스트를 함께 작성합니다.
- 엔티티 테스트는 순수 단위 테스트로 작성합니다.
- 서비스 테스트는 `@SpringBootTest` 기반 통합 테스트를 사용합니다.
- 작업 보고에는 테스트 체크리스트를 먼저 두고, 실제 통과한 항목만 `[x]`로 표시합니다.
- README는 계획이 아니라 **실제로 구현된 범위만** 기록합니다.

## CI/CD

- GitHub Actions CI: `.github/workflows/backend-ci.yml`
- GitHub Actions CD: `.github/workflows/backend-cd.yml`
- 기본 CD 경로: GitHub OIDC -> AWS IAM role -> S3 artifact -> SSM Run Command -> app EC2 `systemd` restart
- 자세한 서버 준비 조건과 GitHub 설정값은 `docs/aws-ec2-github-actions-cicd.md`를 따릅니다.

## Public API / operational note

- 현재 배포 smoke 기준 public health endpoint는 `/health`입니다.
- API는 앱과 내부 운영 검증을 함께 지원하지만, README에는 실제로 열려 있는 현재 surface만 기록합니다.
- 이벤트 수집 API는 로그인 사용자 기준으로 저장되며, 민감 키는 저장 전에 제거합니다.

## Current docs

- `DOCS/00_기준/프로젝트_헌법.md`
- `DOCS/00_기준/통합_개발_테스트_방법론.md`
- `DOCS/00_기준/기술판단_변경_기록_작성_원칙.md`
- `DOCS/15_기능명세/backend/백엔드_기능명세_통합.md`
- `DOCS/15_기능명세/backend/인증_프로필_백엔드_계약_및_요구사항.md`
- `DOCS/15_기능명세/backend/코스_생성_공유_백엔드_계약_및_요구사항.md`

## Analysis SQL examples

```sql
-- 코스 퍼널
select event_name, count(*)
from client_events
where event_name in (
  'course_list_viewed',
  'course_selected',
  'course_detail_viewed',
  'ride_start_clicked',
  'ride_started',
  'ride_completed'
)
group by event_name
order by event_name;

-- 주행 시작 차단 사유
select properties_json ->> 'reason' as reason, count(*)
from client_events
where event_name = 'ride_start_blocked'
group by properties_json ->> 'reason'
order by count(*) desc;

-- 코스별 클릭률
select course_id,
       sum(case when event_name = 'course_impression' then 1 else 0 end) as impressions,
       sum(case when event_name = 'course_selected' then 1 else 0 end) as selections
from client_events
where course_id is not null
group by course_id;

-- 코스별 완료율
select course_id,
       sum(case when event_name = 'ride_started' then 1 else 0 end) as started,
       sum(case when event_name = 'ride_completed' then 1 else 0 end) as completed
from client_events
where course_id is not null
group by course_id;

-- 저장 실패율
select
  sum(case when event_name = 'ride_end_clicked' then 1 else 0 end) as ride_end_clicked,
  sum(case when event_name = 'ride_record_save_failed' then 1 else 0 end) as ride_record_save_failed
from client_events;
```

## Notes

- 기능이 추가되거나 제거되면 **Current scope**와 **Core API surface**를 함께 갱신합니다.
- current 문서와 구현이 어긋나면 README보다 current 문서를 먼저 바로잡습니다.
