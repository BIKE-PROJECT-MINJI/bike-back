package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RideRecordDeletionServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-15T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private AuthService authService;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    @Mock
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @Mock
    private CourseRepository courseRepository;

    private RideRecordDeletionService rideRecordDeletionService;

    @BeforeEach
    void setUp() {
        rideRecordDeletionService = new RideRecordDeletionService(
                authService,
                rideRecordRepository,
                rideRecordPointRepository,
                rideRecordProcessedPointRepository,
                courseRepository,
                FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("소유자의 자유 주행 기록 삭제는 포인트를 지우고 연결된 코스의 원본 기록 참조를 끊는다")
    void deleteRideRecordDeletesOwnedRecordAndDetachesLinkedCourse() {
        UserEntity user = user(1L);
        RideRecordEntity rideRecord = rideRecord(1001L, 1L, "2026-06-10T10:00:00Z");
        CourseEntity linkedCourse = course(2001L, 1L, 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findById(1001L)).willReturn(Optional.of(rideRecord));
        given(courseRepository.findBySourceRideRecordIdIn(List.of(1001L))).willReturn(List.of(linkedCourse));

        rideRecordDeletionService.deleteRideRecord("1", 1001L);

        assertThat(linkedCourse.getSourceRideRecordId()).isNull();
        verify(rideRecordPointRepository).deleteByRideRecordIdIn(List.of(1001L));
        verify(rideRecordProcessedPointRepository).deleteByRideRecordIdIn(List.of(1001L));
        verify(rideRecordRepository).deleteAllByIdInBatch(List.of(1001L));
    }

    @Test
    @DisplayName("다른 사용자의 자유 주행 기록 삭제는 403으로 거절한다")
    void deleteRideRecordRejectsDifferentOwner() {
        UserEntity user = user(1L);
        RideRecordEntity rideRecord = rideRecord(1001L, 2L, "2026-06-10T10:00:00Z");

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findById(1001L)).willReturn(Optional.of(rideRecord));

        assertThatThrownBy(() -> rideRecordDeletionService.deleteRideRecord("1", 1001L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("자유 주행 기록에 접근할 수 없습니다.");

        verifyNoInteractions(rideRecordPointRepository, rideRecordProcessedPointRepository, courseRepository);
        verify(rideRecordRepository, never()).deleteAllByIdInBatch(List.of(1001L));
    }

    @Test
    @DisplayName("없는 자유 주행 기록 삭제는 404를 반환한다")
    void deleteRideRecordRejectsMissingRecord() {
        given(authService.findUserBySubject("1")).willReturn(user(1L));
        given(rideRecordRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> rideRecordDeletionService.deleteRideRecord("1", 999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("자유 주행 기록을 찾을 수 없습니다.");

        verifyNoInteractions(rideRecordPointRepository, rideRecordProcessedPointRepository, courseRepository);
        verify(rideRecordRepository, never()).deleteAllByIdInBatch(List.of(999L));
    }

    @Test
    @DisplayName("30일이 지난 자유 주행 기록 정리는 만료 기록만 삭제하고 연결된 코스 참조를 끊는다")
    void deleteExpiredRideRecordsDeletesOnlyExpiredRecordsAndDetachesLinkedCourses() {
        RideRecordEntity expiredRideRecord = rideRecord(1001L, 1L, "2026-05-15T00:00:00Z");
        CourseEntity linkedCourse = course(2001L, 1L, 1001L);

        given(rideRecordRepository.findByEndedAtLessThanEqual(OffsetDateTime.parse("2026-05-16T00:00:00Z")))
                .willReturn(List.of(expiredRideRecord));
        given(courseRepository.findBySourceRideRecordIdIn(List.of(1001L))).willReturn(List.of(linkedCourse));

        int deletedCount = rideRecordDeletionService.deleteExpiredRideRecords();

        assertThat(deletedCount).isEqualTo(1);
        assertThat(linkedCourse.getSourceRideRecordId()).isNull();
        verify(rideRecordPointRepository).deleteByRideRecordIdIn(List.of(1001L));
        verify(rideRecordProcessedPointRepository).deleteByRideRecordIdIn(List.of(1001L));
        verify(rideRecordRepository).deleteAllByIdInBatch(List.of(1001L));
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private RideRecordEntity rideRecord(Long id, Long ownerUserId, String endedAt) {
        RideRecordEntity rideRecord = new RideRecordEntity(
                ownerUserId,
                OffsetDateTime.parse("2026-05-01T00:00:00Z"),
                OffsetDateTime.parse(endedAt),
                18250,
                3600
        );
        ReflectionTestUtils.setField(rideRecord, "id", id);
        return rideRecord;
    }

    private CourseEntity course(Long id, Long ownerUserId, Long sourceRideRecordId) {
        CourseEntity course = new CourseEntity(
                "퇴근길 코스",
                "삭제된 자유 주행 기록에서 저장한 코스",
                BigDecimal.valueOf(18.2),
                60,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                ownerUserId,
                sourceRideRecordId,
                CourseVisibility.PRIVATE
        );
        ReflectionTestUtils.setField(course, "id", id);
        return course;
    }
}
