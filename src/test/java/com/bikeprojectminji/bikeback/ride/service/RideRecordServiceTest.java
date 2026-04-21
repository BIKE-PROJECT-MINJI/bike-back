package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;

@ExtendWith(MockitoExtension.class)
class RideRecordServiceTest {

    @Mock
    private AuthService authService;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    @Mock
    private RecentLocationCacheService recentLocationCacheService;

    @Mock
    private RideRecordFinalizationService rideRecordFinalizationService;

    @InjectMocks
    private RideRecordService rideRecordService;

    @Test
    @DisplayName("자유 주행 기록 저장은 소유자와 route point를 함께 저장한다")
    void saveRideRecordReturnsPersistedRecordResponse() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(1L, OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(savedRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        ));

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.ownerUserId()).isEqualTo(1L);
        assertThat(response.routePointCount()).isEqualTo(2);
        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
        verify(recentLocationCacheService).saveCompleted(
                eq("1"),
                eq(1001L),
                eq(2),
                eq(BigDecimal.valueOf(37.5671)),
                eq(BigDecimal.valueOf(126.9792)),
                eq(OffsetDateTime.parse("2026-03-29T11:00:00+09:00"))
        );
        verify(rideRecordFinalizationService).requestFinalization(1001L);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 telemetry nullable 필드를 포함해도 저장된다")
    void saveRideRecordAcceptsTelemetryFields() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(1L, OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(savedRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(new RideRecordPointRequest(
                        1,
                        BigDecimal.valueOf(37.5665),
                        BigDecimal.valueOf(126.9780),
                        OffsetDateTime.parse("2026-03-29T10:00:02+09:00"),
                        BigDecimal.valueOf(8.50),
                        BigDecimal.valueOf(5.20),
                        BigDecimal.valueOf(184.0),
                        BigDecimal.valueOf(13.5),
                        BigDecimal.valueOf(2.5),
                        BigDecimal.valueOf(12.5)
                ))
        ));

        assertThat(response.routePointCount()).isEqualTo(1);
    }
}
