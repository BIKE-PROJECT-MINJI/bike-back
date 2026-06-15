package com.bikeprojectminji.bikeback.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.profile.dto.ProfileActivitySummaryResponse;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@ActiveProfiles("test")
class ProfileActivitySummaryIntegrationTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-26T15:30:00Z");

    @Autowired
    private ProfileService profileService;

    @Autowired
    private RideRecordRepository rideRecordRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        rideRecordRepository.deleteAll();
        courseRepository.deleteAll();

        UserEntity user = new UserEntity("external-1", "bikeoasis@example.com", null, "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(clock.instant()).willReturn(FIXED_NOW);
    }

    @Test
    @DisplayName("활동 요약 집계는 READY 주행만 주간과 전체 합계에 포함한다")
    void getMyActivitySummaryAggregatesOnlyReadyRideRecords() {
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.ofHours(9));
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
        CourseEntity courseThisWeek = courseRepository.save(new CourseEntity(
                "이번 주 저장 코스",
                "이번 주 저장된 내 코스만 주간 요약에 포함된다.",
                BigDecimal.valueOf(12.5),
                60,
                1,
                false,
                null,
                null,
                null,
                1L,
                CourseVisibility.PRIVATE
        ));
        CourseEntity otherUserCourse = courseRepository.save(new CourseEntity(
                "다른 사용자 코스",
                "소유자가 다르면 주간 요약에 포함하지 않는다.",
                BigDecimal.valueOf(8.0),
                45,
                2,
                false,
                null,
                null,
                null,
                2L,
                CourseVisibility.PRIVATE
        ));
        jdbcTemplate.update(
                "update courses set created_at = ? where id = ?",
                weekStart.plusDays(1),
                courseThisWeek.getId()
        );
        jdbcTemplate.update(
                "update courses set created_at = ? where id = ?",
                weekStart.plusDays(1),
                otherUserCourse.getId()
        );
        rideRecordRepository.flush();
        courseRepository.flush();
        Statistics statistics = hibernateStatistics();
        statistics.clear();

        ProfileActivitySummaryResponse response = profileService.getMyActivitySummary("1");

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(response.weeklySummary().rideCount()).isEqualTo(1);
        assertThat(response.weeklySummary().distanceKm()).isEqualByComparingTo("15.0");
        assertThat(response.weeklySummary().durationMinutes()).isEqualTo(60);
        assertThat(response.weeklySummary().savedCourseCount()).isEqualTo(1);

        assertThat(response.overallSummary().totalRides()).isEqualTo(2);
        assertThat(response.overallSummary().totalDistanceKm()).isEqualByComparingTo("20.0");
        assertThat(response.overallSummary().avgSpeedKmh()).isEqualByComparingTo("15.0");
    }

    private Statistics hibernateStatistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }
}
