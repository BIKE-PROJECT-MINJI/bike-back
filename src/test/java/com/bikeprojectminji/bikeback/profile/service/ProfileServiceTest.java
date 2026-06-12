package com.bikeprojectminji.bikeback.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.repository.CourseActivityAggregate;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.profile.dto.ProfileActivitySummaryResponse;
import com.bikeprojectminji.bikeback.profile.dto.UpdateProfileRequest;
import com.bikeprojectminji.bikeback.profile.repository.UserPreferenceRepository;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordActivityAggregate;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private UserPreferenceRepository userPreferenceRepository;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private CourseRepository courseRepository;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(
                authService,
                userRepository,
                userPreferenceRepository,
                rideRecordRepository,
                courseRepository,
                Clock.fixed(Instant.parse("2026-05-26T15:30:00Z"), ZoneOffset.UTC)
        );
    }

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
        given(rideRecordRepository.findActivityAggregateByOwnerUserIdAndFinalizationStatus(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(new RideRecordActivityAggregate(12L, 120500L, 24000L, 2L, 24500L, 4200L));
        given(courseRepository.findActivityAggregateByOwnerUserId(
                any(Long.class),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(new CourseActivityAggregate(1L));

        ProfileActivitySummaryResponse response = profileService.getMyActivitySummary("1");

        assertThat(response.weeklySummary().distanceKm()).isEqualByComparingTo("24.5");
        assertThat(response.weeklySummary().rideCount()).isEqualTo(2);
        assertThat(response.weeklySummary().durationMinutes()).isEqualTo(70);
        assertThat(response.weeklySummary().savedCourseCount()).isEqualTo(1);
        assertThat(response.overallSummary().totalDistanceKm()).isEqualByComparingTo("120.5");
        assertThat(response.overallSummary().totalRides()).isEqualTo(12);
        assertThat(response.overallSummary().avgSpeedKmh()).isEqualByComparingTo("18.1");
        assertThat(response.overallSummary().totalElevationM()).isZero();
        verify(rideRecordRepository).findActivityAggregateByOwnerUserIdAndFinalizationStatus(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        );
        verify(courseRepository).findActivityAggregateByOwnerUserId(
                any(Long.class),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        );
        verifyNoMoreInteractions(rideRecordRepository, courseRepository);
    }

    @Test
    @DisplayName("내 활동 요약 주간 범위는 한국 시간 월요일 00시부터 계산한다")
    void getMyActivitySummaryUsesKoreaWeekWindow() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);

        profileService.getMyActivitySummary("1");

        ArgumentCaptor<OffsetDateTime> startCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(rideRecordRepository).findActivityAggregateByOwnerUserIdAndFinalizationStatus(
                eq(1L),
                eq(RideRecordFinalizationStatus.READY.name()),
                startCaptor.capture(),
                endCaptor.capture()
        );
        assertThat(startCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-05-25T00:00:00+09:00"));
        assertThat(endCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-05-31T23:59:59.999999999+09:00"));
    }

    @Test
    @DisplayName("내 활동 요약 조회는 이번 주 기록이 없어도 0값 주간 요약을 반환한다")
    void getMyActivitySummaryReturnsZeroWeeklySummaryWhenWeekIsEmpty() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findActivityAggregateByOwnerUserIdAndFinalizationStatus(
                any(Long.class),
                eq(RideRecordFinalizationStatus.READY.name()),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class)
        ))
                .willReturn(new RideRecordActivityAggregate(4L, 50500L, 7200L, 0L, null, null));
        given(courseRepository.findActivityAggregateByOwnerUserId(any(Long.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .willReturn(new CourseActivityAggregate(0L));

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
