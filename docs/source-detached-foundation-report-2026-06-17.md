# sourceDetached 보강 구현 보고서

## 작업 목적

커뮤니티/팟 후속 기능에서 Course가 원본 자유 주행 기록과 어떤 관계인지 명확히 구분하기 위해 `sourceDetached` 상태를 추가했다. 기존에는 `sourceRideRecordId == null`만으로 “처음부터 원본이 없는 Course”와 “원본 RideRecord 삭제로 분리된 Course”를 구분할 수 없었다.

## 변경 요약

- `courses.source_detached` 컬럼을 추가했다.
- `CourseEntity`에 `sourceDetached` 상태와 조회 메서드를 추가했다.
- `CourseEntity.detachRideRecordSource()`는 원본 RideRecord가 실제로 연결되어 있던 Course만 `sourceDetached=true`로 바꾼다.
- Course 상세/쓰기 응답 DTO에 `sourceDetached`를 추가했다.
- RideRecord 삭제 시 연결 Course는 삭제하지 않고 `sourceRideRecordId=null`, `sourceDetached=true`로 유지한다.
- 처음부터 source 없이 생성된 Course는 `sourceRideRecordId=null`, `sourceDetached=false`를 유지한다.

## 정책

| 상황 | sourceRideRecordId | sourceDetached |
|---|---:|---:|
| 자유 주행 기록에서 저장된 Course | 값 있음 | false |
| 원본 RideRecord 삭제로 분리된 Course | null | true |
| 처음부터 원본 RideRecord 없이 만든 Course | null | false |

## API 계약 영향

- `GET /api/v1/courses/{courseId}` 응답 `data.sourceDetached`가 추가됐다.
- `POST /api/v1/courses`, `PUT /api/v1/courses/{courseId}`, `PATCH /api/v1/courses/{courseId}/visibility` 응답 `data.sourceDetached`가 추가됐다.
- 기존 요청 형식과 `sourceRideRecordId` 필드는 유지된다.
- 응답 필드 추가라 기존 클라이언트에는 하위 호환 변경으로 본다.
- 공개 Course 목록 응답에는 이번 PR에서 `sourceDetached`를 추가하지 않았다.

## 변경 파일

- `src/main/java/com/bikeprojectminji/bikeback/course/entity/CourseEntity.java`
- `src/main/java/com/bikeprojectminji/bikeback/course/dto/CourseDetailResponse.java`
- `src/main/java/com/bikeprojectminji/bikeback/course/dto/CourseWriteResponse.java`
- `src/main/java/com/bikeprojectminji/bikeback/course/service/CourseQueryService.java`
- `src/main/java/com/bikeprojectminji/bikeback/course/service/CourseService.java`
- `src/main/resources/db/migration/V27__add_course_source_detached.sql`
- `src/test/resources/schema-h2.sql`
- `src/test/java/com/bikeprojectminji/bikeback/course/entity/CourseEntitySourceDetachedTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/course/service/CourseQueryServiceTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/course/service/CourseServiceTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/course/controller/CourseControllerReadTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/course/controller/CourseControllerMutationTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/ride/service/RideRecordDeletionServiceTest.java`
- `src/test/java/com/bikeprojectminji/bikeback/ride/service/RideRecordDeletionIntegrationTest.java`

## TDD 근거

구현 전 다음 실패를 먼저 확인했다.

- `CourseEntity.isSourceDetached()` 미구현
- `CourseDetailResponse.sourceDetached()` 미구현
- `CourseWriteResponse.sourceDetached()` 미구현
- `sourceDetached` 포함 생성자 미구현

이후 최소 구현으로 targeted 테스트를 통과시켰다.

## 실행한 검증

```bash
./gradlew --no-daemon test --tests '*RideRecordDeletion*' --tests '*Course*'
```

결과:

- 성공
- 실행 시간: 3분 48초
- Course/RideRecord deletion 관련 targeted 테스트 통과

전체 테스트:

```bash
./gradlew --no-daemon test
```

결과:

- 성공
- 실행 시간: 6분 58초

## 남은 리스크

- 기존 운영 DB의 과거 데이터 중 `sourceRideRecordId`가 이미 null인 Course는 “처음부터 원본 없음”과 “과거 삭제로 분리됨”을 자동 판별할 근거가 없다. 이번 migration은 기본값 `false`만 부여한다.
- 공개 Course 목록에는 `sourceDetached`를 아직 노출하지 않았다. 공개 목록 read model과 publication 구조가 확정되는 PR에서 별도로 다루는 것이 맞다.
- 이 PR은 `course_publications`, moderation, public list, 팟 도메인을 구현하지 않는다.

## 다음 PR 추천

1. `course_publications` 기반 공개/비공개 책임 분리
2. publicationId 기준 복사 정책과 route snapshot 정책 확정
3. report/moderation 자동 숨김 정책 전환
4. 공개 Course 목록 projection/read model 성능 개선
