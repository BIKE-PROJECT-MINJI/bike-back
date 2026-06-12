package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.achievement.service.AchievementCompletionDispatcher;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointRequest;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseVisibilityRequest;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRouteGeometryRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
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
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @Mock
    private AuthService authService;

    @Mock
    private CourseRouteSnapshotService courseRouteSnapshotService;

    @Mock
    private CourseRouteGeometryRepository courseRouteGeometryRepository;

    @Mock
    private AchievementCompletionDispatcher achievementCompletionDispatcher;

    @InjectMocks
    private CourseService courseService;

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
        InOrder inOrder = inOrder(courseRoutePointRepository, courseRouteGeometryRepository);
        inOrder.verify(courseRoutePointRepository).saveAll(any());
        inOrder.verify(courseRoutePointRepository).flush();
        inOrder.verify(courseRouteGeometryRepository).refreshRouteLine(2001L);
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 업적 지급 후처리를 별도 dispatcher에 맡긴다")
    void createCourseFromRideRecordDispatchesAchievementGrant() {
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
        verify(achievementCompletionDispatcher).dispatchAfterCommit(any());
    }

    @Test
    @DisplayName("코스 수정은 route point 교체 후 route_line_geom을 다시 계산한다")
    void updateCourseRefreshesRouteLineAfterRoutePointReplacement() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        CourseEntity course = new CourseEntity("기존 코스", "설명", BigDecimal.valueOf(18.3), 10, 1, false, null, null, null, 1L, CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(course, "id", 2001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(2001L)).willReturn(Optional.of(course));
        given(courseRepository.save(any(CourseEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        courseService.updateCourse("1", 2001L, new UpdateCourseRequest(
                "수정 코스",
                "수정 설명",
                "PRIVATE",
                List.of(
                        new CourseRoutePointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792)),
                        new CourseRoutePointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780))
                )
        ));

        InOrder inOrder = inOrder(courseRoutePointRepository, courseRouteGeometryRepository);
        inOrder.verify(courseRoutePointRepository).saveAll(any());
        inOrder.verify(courseRoutePointRepository).flush();
        inOrder.verify(courseRouteGeometryRepository).refreshRouteLine(2001L);
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
    @DisplayName("기록 기반 코스 생성은 타인 자유 주행 기록이면 NotFoundException을 던진다")
    void createCourseFromRideRecordThrowsNotFoundForOtherOwnerRideRecord() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(1001L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(1001L, "한강 코스", "설명", "PRIVATE")
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("자유 주행 기록을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 finalization 실패 상태면 BadRequestException을 던진다")
    void createCourseFromRideRecordThrowsWhenFinalizationFailed() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(1L, java.time.OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), java.time.OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(rideRecord, "id", 1001L);
        rideRecord.markFailed(java.time.OffsetDateTime.parse("2026-03-29T11:01:00+09:00"), "processor failed");

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(1001L, 1L)).willReturn(Optional.of(rideRecord));

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(1001L, "한강 코스", "설명", "PRIVATE")
        ))
                .isInstanceOf(BadRequestException.class)
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
    @DisplayName("코스 수정은 route point 위도 범위를 검증한다")
    void updateCourseRejectsInvalidRoutePointLatitude() {
        assertThatThrownBy(() -> courseService.updateCourse("1", 2001L, new UpdateCourseRequest(
                "한강 코스",
                "설명",
                "PRIVATE",
                List.of(new CourseRoutePointRequest(1, BigDecimal.valueOf(91), BigDecimal.valueOf(126.9780)))
        )))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("routePoints.latitude는 -90 이상 90 이하여야 합니다.");
    }

    @Test
    @DisplayName("코스 수정은 route point 경도 범위를 검증한다")
    void updateCourseRejectsInvalidRoutePointLongitude() {
        assertThatThrownBy(() -> courseService.updateCourse("1", 2001L, new UpdateCourseRequest(
                "한강 코스",
                "설명",
                "PRIVATE",
                List.of(new CourseRoutePointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(181)))
        )))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("routePoints.longitude는 -180 이상 180 이하여야 합니다.");
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
