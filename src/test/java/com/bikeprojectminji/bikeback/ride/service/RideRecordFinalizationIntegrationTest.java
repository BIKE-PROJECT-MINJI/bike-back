package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.service.CourseService;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheStore;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordListResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class RideRecordFinalizationIntegrationTest {

    @Autowired
    private RideRecordService rideRecordService;

    @Autowired
    private CourseService courseService;

    @Autowired
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RecentLocationCacheStore recentLocationCacheStore;

    private UserEntity savedUser;

    @BeforeEach
    void setUp() {
        savedUser = new UserEntity("external-1", "bikeoasis@example.com", null, "bikeoasis", null);
        org.springframework.test.util.ReflectionTestUtils.setField(savedUser, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(savedUser);
        given(recentLocationCacheStore.find("1")).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("자유 주행 기록 저장 후 finalization이 완료되면 processed route를 만들고 코스 생성을 허용한다")
    void saveRideRecordFinalizesAndAllowsCourseCreation() throws Exception {
        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.56655), BigDecimal.valueOf(126.9785)),
                        new RideRecordPointRequest(3, BigDecimal.valueOf(37.56660), BigDecimal.valueOf(126.9790)),
                        new RideRecordPointRequest(4, BigDecimal.valueOf(37.56665), BigDecimal.valueOf(126.9795))
                )
        ));

        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");

        RideRecordFinalizationStatusResponse status = awaitReady(response.rideRecordId());
        assertThat(status.status()).isEqualTo("READY");

        List<RideRecordProcessedPointEntity> processedPoints = rideRecordProcessedPointRepository.findByRideRecordIdOrderByPointOrderAsc(response.rideRecordId());
        assertThat(processedPoints).isNotEmpty();

        CourseWriteResponse courseResponse = courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(response.rideRecordId(), "한강 코스", "설명", "PRIVATE")
        );

        assertThat(courseResponse.courseId()).isNotNull();
        assertThat(courseResponse.sourceRideRecordId()).isEqualTo(response.rideRecordId());

        RideRecordListResponse listResponse = rideRecordService.listRideRecords("1");
        assertThat(listResponse.items()).hasSize(1);
        assertThat(listResponse.items().get(0).linkedCourseId()).isEqualTo(courseResponse.courseId());

        RideRecordFinalizationStatusResponse detailResponse = rideRecordService.getRideRecordStatus("1", response.rideRecordId());
        assertThat(detailResponse.distanceM()).isEqualTo(18250);
        assertThat(detailResponse.durationSec()).isEqualTo(3600);
        assertThat(detailResponse.linkedCourseId()).isEqualTo(courseResponse.courseId());
    }

    private RideRecordFinalizationStatusResponse awaitReady(Long rideRecordId) throws Exception {
        for (int i = 0; i < 20; i++) {
            RideRecordFinalizationStatusResponse status = rideRecordService.getRideRecordStatus("1", rideRecordId);
            if (!"FINALIZING".equals(status.status())) {
                return status;
            }
            Thread.sleep(100L);
        }
        return rideRecordService.getRideRecordStatus("1", rideRecordId);
    }
}
