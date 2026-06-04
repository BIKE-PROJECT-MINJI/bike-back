package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.course.service.CourseService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheStore;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordListResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RideRecordFinalizationIntegrationTest {

    @Autowired
    private RideRecordService rideRecordService;

    @Autowired
    private CourseService courseService;

    @Autowired
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @Autowired
    private RideRecordPointRepository rideRecordPointRepository;

    @Autowired
    private RideRecordRepository rideRecordRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private CourseRoutePointRepository courseRoutePointRepository;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RecentLocationCacheStore recentLocationCacheStore;

    private UserEntity savedUser;

    @BeforeEach
    void setUp() {
        courseRoutePointRepository.deleteAll();
        courseRepository.deleteAll();
        rideRecordProcessedPointRepository.deleteAll();
        rideRecordPointRepository.deleteAll();
        rideRecordRepository.deleteAll();

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

        List<CourseRoutePointEntity> courseRoutePoints = courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseResponse.courseId());
        assertThat(courseRoutePoints).hasSize(processedPoints.size());
        assertThat(courseRoutePoints)
                .extracting(CourseRoutePointEntity::getPointOrder)
                .containsExactlyElementsOf(processedPoints.stream()
                        .map(RideRecordProcessedPointEntity::getPointOrder)
                        .toList());
        assertThat(courseRoutePoints.get(0).getLatitude()).isEqualByComparingTo(processedPoints.get(0).getLatitude());
        assertThat(courseRoutePoints.get(courseRoutePoints.size() - 1).getLongitude())
                .isEqualByComparingTo(processedPoints.get(processedPoints.size() - 1).getLongitude());

        RideRecordListResponse listResponse = rideRecordService.listRideRecords("1");
        assertThat(listResponse.items()).hasSize(1);
        assertThat(listResponse.items().get(0).linkedCourseId()).isEqualTo(courseResponse.courseId());

        RideRecordFinalizationStatusResponse detailResponse = rideRecordService.getRideRecordStatus("1", response.rideRecordId());
        assertThat(detailResponse.distanceM()).isEqualTo(18250);
        assertThat(detailResponse.durationSec()).isEqualTo(3600);
        assertThat(detailResponse.linkedCourseId()).isEqualTo(courseResponse.courseId());
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 같은 자유 주행 기록으로 중복 생성할 수 없다")
    void createCourseFromRideRecordRejectsDuplicateSourceRideRecord() throws Exception {
        RideRecordResponse response = rideRecordService.saveRideRecord("1", validRideRecordRequest("android-ride-duplicate"));
        awaitReady(response.rideRecordId());

        courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(response.rideRecordId(), "첫 저장 코스", "설명", "PRIVATE")
        );

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(response.rideRecordId(), "중복 저장 코스", "설명", "PRIVATE")
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 코스로 저장된 자유 주행 기록입니다.");
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 READY라도 processed point가 없으면 거절한다")
    void createCourseFromRideRecordRejectsReadyRecordWithoutProcessedPoints() {
        RideRecordEntity rideRecord = new RideRecordEntity(
                1L,
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                18250,
                3600
        );
        rideRecord.markReady(OffsetDateTime.parse("2026-04-21T11:01:00+09:00"));
        RideRecordEntity saved = rideRecordRepository.save(rideRecord);

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(saved.getId(), "빈 경로 코스", "설명", "PRIVATE")
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("최종 경로가 비어 있어 코스를 생성할 수 없습니다.");
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 finalization 완료 전이면 거절한다")
    void createCourseFromRideRecordRejectsFinalizingRecord() {
        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                1L,
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                18250,
                3600
        ));

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(rideRecord.getId(), "보정 전 코스", "설명", "PRIVATE")
        ))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("경로 보정이 아직 완료되지 않았습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("기록 기반 코스 생성은 타인 자유 주행 기록이면 찾을 수 없다")
    void createCourseFromRideRecordRejectsOtherOwnerRideRecord() {
        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                2L,
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                18250,
                3600
        ));

        assertThatThrownBy(() -> courseService.createCourseFromRideRecord(
                "1",
                new CreateCourseFromRideRecordRequest(rideRecord.getId(), "타인 기록 코스", "설명", "PRIVATE")
        ))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("자유 주행 기록을 찾을 수 없습니다.");
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

    private CreateRideRecordRequest validRideRecordRequest(String clientRideId) {
        return new CreateRideRecordRequest(
                clientRideId,
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.56655), BigDecimal.valueOf(126.9785)),
                        new RideRecordPointRequest(3, BigDecimal.valueOf(37.56660), BigDecimal.valueOf(126.9790)),
                        new RideRecordPointRequest(4, BigDecimal.valueOf(37.56665), BigDecimal.valueOf(126.9795))
                )
        );
    }
}
