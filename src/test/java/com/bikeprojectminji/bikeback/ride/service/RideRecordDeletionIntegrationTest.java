package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
class RideRecordDeletionIntegrationTest {

    @Autowired
    private RideRecordDeletionService rideRecordDeletionService;

    @Autowired
    private RideRecordRepository rideRecordRepository;

    @Autowired
    private RideRecordPointRepository rideRecordPointRepository;

    @Autowired
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @Autowired
    private CourseRepository courseRepository;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        courseRepository.deleteAll();
        rideRecordProcessedPointRepository.deleteAll();
        rideRecordPointRepository.deleteAll();
        rideRecordRepository.deleteAll();

        UserEntity user = new UserEntity("external-1", "bikeoasis@example.com", null, "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
    }

    @Test
    @DisplayName("웹 HUD summary와 trace로 생성된 기록 삭제는 포인트를 지우고 연결 코스만 분리한다")
    void deleteRideRecordCreatedByWebHudSummaryAndTraceDeletesPointsAndDetachesCourse() {
        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                1L,
                "web-hud-ride-delete-001",
                OffsetDateTime.parse("2026-06-16T10:00:00+09:00"),
                OffsetDateTime.parse("2026-06-16T10:30:00+09:00"),
                8200,
                1800
        ));
        rideRecordPointRepository.saveAll(List.of(
                new RideRecordPointEntity(rideRecord.getId(), 1, BigDecimal.valueOf(37.5665000), BigDecimal.valueOf(126.9780000)),
                new RideRecordPointEntity(rideRecord.getId(), 2, BigDecimal.valueOf(37.5671000), BigDecimal.valueOf(126.9792000))
        ));
        rideRecordProcessedPointRepository.saveAll(List.of(
                new RideRecordProcessedPointEntity(rideRecord.getId(), 1, BigDecimal.valueOf(37.5665000), BigDecimal.valueOf(126.9780000)),
                new RideRecordProcessedPointEntity(rideRecord.getId(), 2, BigDecimal.valueOf(37.5671000), BigDecimal.valueOf(126.9792000))
        ));
        CourseEntity linkedCourse = courseRepository.save(new CourseEntity(
                "웹 HUD 저장 코스",
                "삭제 대상 자유 주행 기록에서 만든 코스",
                BigDecimal.valueOf(8.2),
                30,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5665000),
                BigDecimal.valueOf(126.9780000),
                1L,
                rideRecord.getId(),
                CourseVisibility.PRIVATE
        ));

        assertThatCode(() -> rideRecordDeletionService.deleteRideRecord("1", rideRecord.getId()))
                .doesNotThrowAnyException();

        assertThat(rideRecordRepository.findById(rideRecord.getId())).isEmpty();
        assertThat(rideRecordPointRepository.countByRideRecordId(rideRecord.getId())).isZero();
        assertThat(rideRecordProcessedPointRepository.countByRideRecordId(rideRecord.getId())).isZero();
        assertThat(courseRepository.findById(linkedCourse.getId()))
                .get()
                .extracting(CourseEntity::getSourceRideRecordId)
                .isNull();
    }
}
