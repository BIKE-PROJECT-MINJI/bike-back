package com.bikeprojectminji.bikeback.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.profile.dto.ProfileActivitySummaryResponse;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@ActiveProfiles("test")
class ProfileActivitySummaryIntegrationTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private RideRecordRepository rideRecordRepository;

    @Autowired
    private CourseRepository courseRepository;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        rideRecordRepository.deleteAll();
        courseRepository.deleteAll();

        UserEntity user = new UserEntity("external-1", "bikeoasis@example.com", null, "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
    }

    @Test
    @DisplayName("활동 요약 집계는 READY 주행만 주간과 전체 합계에 포함한다")
    void getMyActivitySummaryAggregatesOnlyReadyRideRecords() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime weekStart = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay()
                .atOffset(now.getOffset());

        RideRecordEntity readyThisWeek = new RideRecordEntity(
                1L,
                weekStart.plusDays(1).plusHours(8),
                weekStart.plusDays(1).plusHours(9),
                15000,
                3600
        );
        readyThisWeek.markReady(weekStart.plusDays(1).plusHours(9).plusMinutes(1));

        RideRecordEntity failedThisWeek = new RideRecordEntity(
                1L,
                weekStart.plusDays(2).plusHours(8),
                weekStart.plusDays(2).plusHours(9),
                99999,
                9999
        );
        failedThisWeek.markFailed(weekStart.plusDays(2).plusHours(9).plusMinutes(1), "failed");

        RideRecordEntity finalizingThisWeek = new RideRecordEntity(
                1L,
                weekStart.plusDays(3).plusHours(8),
                weekStart.plusDays(3).plusHours(9),
                88888,
                8888
        );

        RideRecordEntity readyBeforeThisWeek = new RideRecordEntity(
                1L,
                weekStart.minusDays(3).minusHours(1),
                weekStart.minusDays(3),
                5000,
                1200
        );
        readyBeforeThisWeek.markReady(weekStart.minusDays(2).plusMinutes(10));

        rideRecordRepository.save(readyThisWeek);
        rideRecordRepository.save(failedThisWeek);
        rideRecordRepository.save(finalizingThisWeek);
        rideRecordRepository.save(readyBeforeThisWeek);

        ProfileActivitySummaryResponse response = profileService.getMyActivitySummary("1");

        assertThat(response.weeklySummary().rideCount()).isEqualTo(1);
        assertThat(response.weeklySummary().distanceKm()).isEqualByComparingTo("15.0");
        assertThat(response.weeklySummary().durationMinutes()).isEqualTo(60);

        assertThat(response.overallSummary().totalRides()).isEqualTo(2);
        assertThat(response.overallSummary().totalDistanceKm()).isEqualByComparingTo("20.0");
        assertThat(response.overallSummary().avgSpeedKmh()).isEqualByComparingTo("15.0");
    }
}
