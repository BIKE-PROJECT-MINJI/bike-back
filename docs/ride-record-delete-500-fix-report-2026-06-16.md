# 자유주행 기록 삭제 500 오류 수정 보고서

## 1. 작업 요약

- 대상 API: `DELETE /api/v1/ride-records/{rideRecordId}`
- 기존 Go/No-Go 판단: No-Go
- 기존 문제: 웹 HUD에서 `summary -> trace -> DELETE` 순서로 실제 HTTP 호출 시 계약상 기대한 `204 No Content`가 아니라 `500`이 반환됐다.
- 수정 결과: 실제 PostGIS/Redis/bootRun 환경에서 같은 흐름을 재실행했고, DELETE `204`, body `0 byte`, DB record/point 삭제를 확인했다.
- PR: https://github.com/BIKE-PROJECT-MINJI/bike-back/pull/45
- merge commit: `e6f9e75`

## 2. 원인 요약

운영 DB migration 기준으로 `ride_record_points`와 `ride_record_processed_points`는 `ride_records`에 `ON DELETE CASCADE`로 연결되어 있다.

기존 삭제 로직은 아래 순서였다.

1. `RideRecordDeletionService`가 raw point를 `deleteByRideRecordIdIn`으로 삭제한다.
2. processed point도 같은 방식으로 삭제한다.
3. 부모 `ride_records`를 `deleteAllByIdInBatch`로 삭제한다.

문제는 Spring Data derived delete가 row 단위 entity delete처럼 동작하면서 Hibernate flush queue에 child entity delete action을 남길 수 있다는 점이었다. 그 상태에서 부모 `ride_records`를 삭제하면 DB의 `ON DELETE CASCADE`가 child row를 먼저 제거한다. 이후 commit flush 시 Hibernate가 이미 사라진 `ride_record_points.id`를 다시 삭제하려 하면서 아래 오류가 발생했다.

```text
ObjectOptimisticLockingFailureException
Batch update returned unexpected row count from update [0]
actual row count: 0; expected: 1
statement executed: delete from ride_record_points where id=?
```

즉, 원인은 프론트 payload나 JWT 문제가 아니라 **DB cascade와 JPA entity delete action의 중복 삭제 충돌**이었다.

## 3. 수정 방식

삭제 책임을 다음처럼 정리했다.

- Course는 삭제하지 않고 `sourceRideRecordId`만 `null`로 분리한다.
- raw point와 processed point는 JPQL bulk delete로 삭제한다.
- 부모 `ride_records`는 기존처럼 batch delete한다.

변경 핵심은 repository 삭제 메서드다.

```java
@Modifying(flushAutomatically = true)
@Query("delete from RideRecordPointEntity p where p.rideRecordId in :rideRecordIds")
void deleteByRideRecordIdIn(@Param("rideRecordIds") List<Long> rideRecordIds);
```

JPQL bulk delete는 child entity delete action을 개별 엔티티 단위로 쌓지 않으므로, 부모 삭제 cascade와 Hibernate flush가 같은 row를 중복 삭제하지 않는다.

## 4. 변경 파일

| 파일 | 변경 내용 |
| --- | --- |
| `src/main/java/com/bikeprojectminji/bikeback/ride/repository/RideRecordPointRepository.java` | raw point 삭제를 JPQL bulk delete로 변경 |
| `src/main/java/com/bikeprojectminji/bikeback/ride/repository/RideRecordProcessedPointRepository.java` | processed point 삭제를 JPQL bulk delete로 변경 |
| `src/test/resources/schema-h2.sql` | H2 테스트 스키마에 운영과 같은 FK `ON DELETE CASCADE` 추가 |
| `src/test/java/com/bikeprojectminji/bikeback/ride/service/RideRecordDeletionIntegrationTest.java` | summary+trace 생성 후 삭제하는 실제 DB 통합 테스트 추가 |

## 5. 추가/수정한 테스트

새로 추가한 테스트:

- `RideRecordDeletionIntegrationTest`
  - 웹 HUD summary 형태의 ride record 생성
  - trace에 해당하는 raw point 생성
  - processed point 생성
  - source ride record를 참조하는 Course 생성
  - 삭제 실행
  - ride record/raw point/processed point 삭제 확인
  - Course는 남아 있고 `sourceRideRecordId`만 분리됐는지 확인

테스트 스키마 보강:

- 기존 H2 `schema-h2.sql`에는 운영 DB의 `ON DELETE CASCADE`가 없어 실제 운영형 충돌을 잡지 못했다.
- 이번 수정으로 H2 테스트도 운영 FK 조건과 더 가까워졌다.

## 6. 검증 명령과 결과

### 6-1. RED 확인

product code 수정 전 새 통합 테스트 실행:

```bash
./gradlew --no-daemon test --tests '*RideRecordDeletionIntegrationTest'
```

결과:

```text
FAILED
ObjectOptimisticLockingFailureException
statement executed: delete from ride_record_points where id=?
```

리뷰/QA에서 확인한 실제 HTTP 500 로그와 같은 계열의 오류가 재현됐다.

### 6-2. GREEN 확인

수정 후 같은 테스트 재실행:

```bash
./gradlew --no-daemon test --tests '*RideRecordDeletionIntegrationTest'
```

결과:

```text
BUILD SUCCESSFUL in 2m 5s
```

### 6-3. 관련 테스트

```bash
./gradlew --no-daemon test \
  --tests '*RideRecordControllerTest' \
  --tests '*RideRecordServiceTest' \
  --tests '*RideRecordDeletionServiceTest' \
  --tests '*RideRecordDeletionIntegrationTest' \
  --tests '*RideRecordRetentionSchedulerTest'
```

결과:

```text
BUILD SUCCESSFUL in 1m 51s
```

### 6-4. 전체 테스트

```bash
./gradlew --no-daemon test
```

결과:

```text
BUILD SUCCESSFUL in 5m 37s
```

## 7. 실제 HTTP smoke 결과

환경:

- backend: `127.0.0.1:8080`
- PostGIS: 임시 컨테이너 `127.0.0.1:55433`
- Redis: 임시 컨테이너 `127.0.0.1:6380`
- 인증: `/api/v1/auth/register`로 발급한 실제 JWT
- weather/routing: smoke 범위 밖. 서버 기동에는 fake 설정 사용

호출 순서:

1. `GET /health`
2. `POST /api/v1/auth/register`
3. `POST /api/v1/ride-records/summary`
4. `POST /api/v1/ride-records/{rideRecordId}/trace`
5. `DELETE /api/v1/ride-records/{rideRecordId}`
6. DB에서 record/point 잔여 row 확인

결과:

```text
health_status=200
register_status=200
summary_status=200
ride_record_id=1
trace_status=200
delete_result=204 0
delete_body_bytes=0
ride_records=0
raw_points=0
processed_points=0
```

DELETE 계약 확인:

- status: `204 No Content`
- body: `0 byte`
- raw point: 삭제됨
- processed point: 삭제됨
- ride record: 삭제됨

## 8. API 계약 영향

API 요청/응답 계약은 바뀌지 않았다.

- 성공: `204 No Content`
- body: 없음
- 인증 필요
- 비로그인: 401
- 타인 기록: 403
- 기록 없음: 404

프론트는 기존처럼 DELETE 성공 시 JSON body를 파싱하지 않고 204 빈 응답으로 처리하면 된다.

## 9. 포트폴리오 근거 문장

- 실제 HTTP smoke에서만 드러난 `DELETE` 500 blocker를 재현 가능한 통합 테스트로 먼저 고정하고 수정했다.
- 운영 DB의 `ON DELETE CASCADE` 조건을 테스트 스키마에 반영해, Mockito/MockMvc 중심 테스트가 놓친 JPA flush 충돌을 회귀 테스트로 잠갔다.
- JPA derived delete와 DB cascade의 중복 삭제 문제를 JPQL bulk delete로 정리해, 삭제 책임과 flush 타이밍을 명확히 했다.
- 수정 후 실제 `register -> summary -> trace -> DELETE` 흐름에서 DELETE `204`, body `0 byte`, DB 잔여 record/point `0건`을 확인했다.

## 10. 남은 리스크

- 이번 작업은 `DELETE /api/v1/ride-records/{rideRecordId}` 500 blocker 수정에 한정했다.
- GraphHopper self-host/hosted fallback 검증은 이번 범위가 아니다.
- smoke 명령은 수동 실행으로 검증했다. 후속 리뷰/QA 재현성을 높이려면 같은 흐름을 `ops/smoke/` 스크립트로 승격하는 것이 좋다.
