package com.bikeprojectminji.bikeback.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.profile.dto.ProfileActivitySummaryResponse;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.OffsetDateTime;
import com.bikeprojectminji.bikeback.profile.dto.UpdateProfileRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private AuthService authService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private ProfileService profileService;

    @Test
    @DisplayName("내 프로필 조회는 현재 사용자의 최소 프로필을 응답한다")
    void getMyProfileReturnsCurrentUserProfile() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);

        assertThat(profileService.getMyProfile("1").email()).isEqualTo("bikeoasis@example.com");
    }

    @Test
    @DisplayName("내 프로필 수정은 저장 후 응답을 반환한다")
    void updateMyProfileSavesAndReturnsProfile() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        assertThat(profileService.updateMyProfile("1", new UpdateProfileRequest("new-name", "https://example.com/me.png"))
                .displayName()).isEqualTo("new-name");
    }

    @Test
    @DisplayName("내 활동 요약 조회는 주간과 전체 요약을 함께 반환한다")
    void getMyActivitySummaryReturnsWeeklyAndOverallSummary() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.countByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(2L);
        given(rideRecordRepository.sumDistanceMByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(24500L);
        given(rideRecordRepository.sumDurationSecByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(4200L);
        given(courseRepository.countByOwnerUserIdAndCreatedAtBetween(any(Long.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(1L);

        given(rideRecordRepository.countByOwnerUserIdAndFinalizationStatus(1L, RideRecordFinalizationStatus.READY.name())).willReturn(12L);
        given(rideRecordRepository.sumDistanceMByOwnerUserIdAndFinalizationStatus(1L, RideRecordFinalizationStatus.READY.name())).willReturn(120500L);
        given(rideRecordRepository.sumDurationSecByOwnerUserIdAndFinalizationStatus(1L, RideRecordFinalizationStatus.READY.name())).willReturn(24000L);
        given(courseRepository.countByOwnerUserId(1L)).willReturn(5L);

        ProfileActivitySummaryResponse response = profileService.getMyActivitySummary("1");

        assertThat(response.weeklySummary().distanceKm()).isEqualByComparingTo("24.5");
        assertThat(response.weeklySummary().rideCount()).isEqualTo(2);
        assertThat(response.weeklySummary().durationMinutes()).isEqualTo(70);
        assertThat(response.weeklySummary().savedCourseCount()).isEqualTo(1);
        assertThat(response.overallSummary().totalDistanceKm()).isEqualByComparingTo("120.5");
        assertThat(response.overallSummary().totalRides()).isEqualTo(12);
        assertThat(response.overallSummary().avgSpeedKmh()).isEqualByComparingTo("18.1");
        assertThat(response.overallSummary().totalElevationM()).isZero();
    }

    @Test
    @DisplayName("내 활동 요약 조회는 이번 주 기록이 없어도 0값 주간 요약을 반환한다")
    void getMyActivitySummaryReturnsZeroWeeklySummaryWhenWeekIsEmpty() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.countByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(0L);
        given(rideRecordRepository.sumDistanceMByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(null);
        given(rideRecordRepository.sumDurationSecByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(null);
        given(courseRepository.countByOwnerUserIdAndCreatedAtBetween(any(Long.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(0L);

        given(rideRecordRepository.countByOwnerUserIdAndFinalizationStatus(1L, RideRecordFinalizationStatus.READY.name())).willReturn(4L);
        given(rideRecordRepository.sumDistanceMByOwnerUserIdAndFinalizationStatus(1L, RideRecordFinalizationStatus.READY.name())).willReturn(50500L);
        given(rideRecordRepository.sumDurationSecByOwnerUserIdAndFinalizationStatus(1L, RideRecordFinalizationStatus.READY.name())).willReturn(7200L);
        given(courseRepository.countByOwnerUserId(1L)).willReturn(3L);

        ProfileActivitySummaryResponse response = profileService.getMyActivitySummary("1");

        assertThat(response.weeklySummary().distanceKm()).isEqualByComparingTo("0.0");
        assertThat(response.weeklySummary().rideCount()).isZero();
        assertThat(response.weeklySummary().durationMinutes()).isZero();
        assertThat(response.weeklySummary().savedCourseCount()).isZero();
        assertThat(response.overallSummary().totalDistanceKm()).isEqualByComparingTo("50.5");
        assertThat(response.overallSummary().totalRides()).isEqualTo(4);
        assertThat(response.overallSummary().avgSpeedKmh()).isEqualByComparingTo("25.3");
        assertThat(response.overallSummary().totalElevationM()).isZero();
    }
}
