package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;

import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseVisibilityRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseDetailResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private CourseRoutePointRepository courseRoutePointRepository;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    @Mock
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @Mock
    private AuthService authService;

    @InjectMocks
    private CourseService courseService;

    @Test
    @DisplayName("코스 상세 조회는 단건 상세를 응답한다")
    void getCourseDetailReturnsSingleCourse() {
        CourseEntity entity = new CourseEntity(
                "아라뱃길 루트",
                BigDecimal.valueOf(23.4),
                95,
                1
        );
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.PUBLIC);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));

        CourseDetailResponse response = courseService.getCourseDetail(7L, null, null);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.title()).isEqualTo("아라뱃길 루트");
        assertThat(response.distanceKm()).isEqualByComparingTo("23.4");
        assertThat(response.estimatedDurationMin()).isEqualTo(95);
    }

    @Test
    @DisplayName("코스 상세 조회는 없는 코스면 NotFoundException을 던진다")
    void getCourseDetailThrowsWhenCourseDoesNotExist() {
        given(courseRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> courseService.getCourseDetail(999L, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("코스를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("코스 경로 조회는 pointOrder 오름차순 좌표 목록을 응답한다")
    void getCourseRoutePointsReturnsOrderedPoints() {
        CourseEntity entity = new CourseEntity("공개 코스", BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.PUBLIC);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(7L)).willReturn(List.of(
                new CourseRoutePointEntity(7L, 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                new CourseRoutePointEntity(7L, 2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
        ));

        CourseRoutePointsResponse response = courseService.getCourseRoutePoints(7L, null, null);

        assertThat(response.courseId()).isEqualTo(7L);
        assertThat(response.points()).hasSize(2);
        assertThat(response.points().get(0).pointOrder()).isEqualTo(1);
        assertThat(response.points().get(1).pointOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("코스 경로 조회는 없는 코스면 NotFoundException을 던진다")
    void getCourseRoutePointsThrowsWhenCourseDoesNotExist() {
        given(courseRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> courseService.getCourseRoutePoints(999L, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("코스를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("코스가 없으면 빈 목록과 종료 상태를 응답한다")
    void getCoursesReturnsEmptyPage() {
        given(courseRepository.findPublicPageAfter(null, 11)).willReturn(Collections.emptyList());

        CourseListResponse response = courseService.getCourses(null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("limit보다 하나 더 조회되면 hasNext와 nextCursor를 계산한다")
    void getCoursesReturnsNextCursorWhenMorePagesExist() {
        List<CourseEntity> courses = createCourses(11);
        given(courseRepository.findPublicPageAfter(null, 11)).willReturn(courses);

        CourseListResponse response = courseService.getCourses(null, 10);

        assertThat(response.items()).hasSize(10);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("10");
    }

    private List<CourseEntity> createCourses(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> {
                    CourseEntity entity = new CourseEntity(
                            "코스 " + index,
                            BigDecimal.valueOf(index),
                            60 + index,
                            index
                    );
                    ReflectionTestUtils.setField(entity, "id", (long) index);
                    ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.PUBLIC);
                    return entity;
                })
                .toList();
    }

    @Test
    @DisplayName("비공개 코스 상세 조회는 owner가 아니면 ForbiddenException을 던진다")
    void getCourseDetailThrowsWhenPrivateCourseIsNotOwned() {
        CourseEntity entity = new CourseEntity("비공개 코스", BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(entity, "ownerUserId", 9L);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> courseService.getCourseDetail(7L, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스는 공개되지 않았습니다.");
    }

    @Test
    @DisplayName("비공개 코스 상세 조회는 owner면 응답한다")
    void getCourseDetailReturnsPrivateCourseForOwner() {
        CourseEntity entity = new CourseEntity("비공개 코스", BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(entity, "ownerUserId", 1L);
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(authService.findUserBySubject("1")).willReturn(user);

        CourseDetailResponse response = courseService.getCourseDetail(7L, "1", null);

        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 소유자의 자유 주행 기록으로 코스를 만든다")
    void createCourseFromRideRecordCreatesOwnedCourse() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(1L, java.time.OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), java.time.OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(rideRecord, "id", 1001L);
        rideRecord.markReady(java.time.OffsetDateTime.parse("2026-03-29T11:01:00+09:00"));
        CourseEntity savedCourse = new CourseEntity("한강 코스", "설명", BigDecimal.valueOf(18.3), 60, 11, false, null, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780), 1L, 1001L, CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(savedCourse, "id", 2001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(1001L, 1L)).willReturn(Optional.of(rideRecord));
        given(rideRecordProcessedPointRepository.findByRideRecordIdOrderByPointOrderAsc(1001L)).willReturn(List.of(
                new RideRecordProcessedPointEntity(1001L, 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                new RideRecordProcessedPointEntity(1001L, 2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
        ));
        given(courseRepository.findTopByOrderByDisplayOrderDescIdDesc()).willReturn(Optional.of(createCourses(10).get(9)));
        given(courseRepository.save(any(CourseEntity.class))).willReturn(savedCourse);

        CourseWriteResponse response = courseService.createCourseFromRideRecord("1", new CreateCourseFromRideRecordRequest(1001L, "한강 코스", "설명", "PRIVATE"));

        assertThat(response.courseId()).isEqualTo(2001L);
        assertThat(response.ownerUserId()).isEqualTo(1L);
        assertThat(response.visibility()).isEqualTo("PRIVATE");
        assertThat(response.sourceRideRecordId()).isEqualTo(1001L);
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 finalization 완료 전이면 BadRequestException을 던진다")
    void createCourseFromRideRecordThrowsWhenFinalizationNotReady() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(1L, java.time.OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), java.time.OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(rideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(1001L, 1L)).willReturn(Optional.of(rideRecord));

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord("1", new CreateCourseFromRideRecordRequest(1001L, "한강 코스", "설명", "PRIVATE")))
                .isInstanceOf(com.bikeprojectminji.bikeback.global.exception.BadRequestException.class)
                .hasMessage("경로 보정이 아직 완료되지 않았습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("공개 범위 변경은 소유자가 아니면 ForbiddenException을 던진다")
    void updateCourseVisibilityThrowsWhenUserIsNotOwner() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        CourseEntity course = new CourseEntity("한강 코스", "설명", BigDecimal.valueOf(18.3), 10, 1, false, null, null, null, 999L, CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(course, "id", 2001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(2001L)).willReturn(Optional.of(course));

        assertThatThrownBy(() -> courseService.updateCourseVisibility("1", 2001L, new UpdateCourseVisibilityRequest("PUBLIC")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스를 수정할 권한이 없습니다.");
    }

    @Test
    @DisplayName("공개 코스 검색은 PUBLIC 코스만 latest 기준으로 응답한다")
    void searchPublicCoursesReturnsPublicOnlyItems() {
        List<CourseEntity> courses = createCourses(2);
        given(courseRepository.findTop20ByVisibilityAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility.PUBLIC, "한강"))
                .willReturn(courses);

        CourseListResponse response = courseService.searchPublicCourses("한강", "latest");

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("UNLISTED 코스 다운로드는 share token이 맞으면 허용한다")
    void downloadCourseReturnsPayloadForUnlistedWithShareToken() {
        CourseEntity entity = new CourseEntity("공유 코스", BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.UNLISTED);
        ReflectionTestUtils.setField(entity, "shareToken", "share-token");
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(7L)).willReturn(List.of(
                new CourseRoutePointEntity(7L, 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780))
        ));

        CourseDownloadResponse response = courseService.downloadCourse(7L, null, "share-token");

        assertThat(response.courseId()).isEqualTo(7L);
        assertThat(response.visibility()).isEqualTo("UNLISTED");
        assertThat(response.routePoints()).hasSize(1);
    }

    @Test
    @DisplayName("UNLISTED 코스 다운로드는 share token이 없으면 ForbiddenException을 던진다")
    void downloadCourseThrowsWhenUnlistedShareTokenMissing() {
        CourseEntity entity = new CourseEntity("공유 코스", BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.UNLISTED);
        ReflectionTestUtils.setField(entity, "shareToken", "share-token");
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> courseService.downloadCourse(7L, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스에 접근할 권한이 없습니다.");
    }

    @Test
    @DisplayName("공유 정보 조회는 owner에게 share token과 url을 응답한다")
    void getCourseShareInfoReturnsGeneratedShareInfo() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        CourseEntity entity = new CourseEntity("공유 코스", BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", 7L);
        ReflectionTestUtils.setField(entity, "ownerUserId", 1L);
        ReflectionTestUtils.setField(entity, "visibility", CourseVisibility.UNLISTED);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(courseRepository.save(any(CourseEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        CourseShareResponse response = courseService.getCourseShareInfo("1", 7L);

        assertThat(response.shareType()).isEqualTo("UNLISTED_LINK");
        assertThat(response.shareToken()).isNotBlank();
        assertThat(response.shareUrl()).contains("shareToken=");
    }
}
