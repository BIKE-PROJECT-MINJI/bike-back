package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheStore;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
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
class RideRecordTelemetryIntegrationTest {

    @Autowired
    private RideRecordService rideRecordService;

    @Autowired
    private RideRecordPointRepository rideRecordPointRepository;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RecentLocationCacheStore recentLocationCacheStore;

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity("external-1", "bikeoasis@example.com", null, "bikeoasis", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(recentLocationCacheStore.find("1")).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("기존 route point payload만 보내도 저장된다")
    void saveRideRecordSupportsLegacyPayload() {
        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)))
        ));

        List<RideRecordPointEntity> points = rideRecordPointRepository.findByRideRecordIdOrderByPointOrderAsc(response.rideRecordId());
        assertThat(points).hasSize(1);
        assertThat(points.get(0).getCapturedAt()).isNull();
        assertThat(points.get(0).getAccuracyM()).isNull();
    }

    @Test
    @DisplayName("telemetry 필드를 포함하면 nullable 컬럼에 저장된다")
    void saveRideRecordStoresTelemetryFields() {
        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(new RideRecordPointRequest(
                        1,
                        BigDecimal.valueOf(37.5665),
                        BigDecimal.valueOf(126.9780),
                        OffsetDateTime.parse("2026-04-21T10:00:02+09:00"),
                        BigDecimal.valueOf(8.50),
                        BigDecimal.valueOf(5.20),
                        BigDecimal.valueOf(184.0),
                        BigDecimal.valueOf(13.5),
                        BigDecimal.valueOf(2.5),
                        BigDecimal.valueOf(12.5)
                ))
        ));

        List<RideRecordPointEntity> points = rideRecordPointRepository.findByRideRecordIdOrderByPointOrderAsc(response.rideRecordId());
        assertThat(points).hasSize(1);
        assertThat(points.get(0).getCapturedAt()).isEqualTo(OffsetDateTime.parse("2026-04-21T10:00:02+09:00"));
        assertThat(points.get(0).getAccuracyM()).isEqualByComparingTo(BigDecimal.valueOf(8.50));
    }
}
