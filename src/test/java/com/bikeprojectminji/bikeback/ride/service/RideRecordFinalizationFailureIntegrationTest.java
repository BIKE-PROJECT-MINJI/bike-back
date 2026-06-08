package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
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

@SpringBootTest
@ActiveProfiles("test")
class RideRecordFinalizationFailureIntegrationTest {

    @Autowired
    private RideRecordFinalizationProcessor rideRecordFinalizationProcessor;

    @Autowired
    private RideRecordRepository rideRecordRepository;

    @Autowired
    private RideRecordPointRepository rideRecordPointRepository;

    @Autowired
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @MockitoBean
    private RideRouteCanonicalizer rideRouteCanonicalizer;

    @BeforeEach
    void setUp() {
        rideRecordProcessedPointRepository.deleteAll();
        rideRecordPointRepository.deleteAll();
        rideRecordRepository.deleteAll();
    }

    @Test
    @DisplayName("READY 기록 재처리 실패는 기존 processed point를 보존하고 상태만 FAILED로 남긴다")
    void failedRegenerationPreservesExistingProcessedPoints() {
        RideRecordEntity rideRecord = rideRecordRepository.saveAndFlush(new RideRecordEntity(
                1L,
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                820,
                90
        ));
        Long rideRecordId = rideRecord.getId();
        rideRecordPointRepository.saveAllAndFlush(List.of(
                rawPoint(rideRecordId, 1, "37.4812", "126.9527"),
                rawPoint(rideRecordId, 2, "37.4824", "126.9553")
        ));
        rideRecordProcessedPointRepository.saveAllAndFlush(List.of(
                new RideRecordProcessedPointEntity(rideRecordId, 1, new BigDecimal("37.4800"), new BigDecimal("126.9500")),
                new RideRecordProcessedPointEntity(rideRecordId, 2, new BigDecimal("37.4810"), new BigDecimal("126.9510"))
        ));
        rideRecord.markReady(OffsetDateTime.parse("2026-04-21T11:01:00+09:00"));
        rideRecord.markFinalizing(OffsetDateTime.parse("2026-04-21T11:02:00+09:00"));
        rideRecordRepository.saveAndFlush(rideRecord);
        given(rideRouteCanonicalizer.canonicalize(anyList())).willReturn(List.of(
                new RideRecordPointRequest(1, new BigDecimal("37.4900"), new BigDecimal("126.9600")),
                new RideRecordPointRequest(1, new BigDecimal("37.4910"), new BigDecimal("126.9610"))
        ));

        rideRecordFinalizationProcessor.finalizeRideRecord(rideRecordId);

        RideRecordEntity failedRideRecord = rideRecordRepository.findById(rideRecordId).orElseThrow();
        assertThat(failedRideRecord.getFinalizationStatus()).isEqualTo(RideRecordFinalizationStatus.FAILED);
        List<RideRecordProcessedPointEntity> processedPoints = rideRecordProcessedPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecordId);
        assertThat(processedPoints).hasSize(2);
        assertThat(processedPoints.get(0).getLatitude()).isEqualByComparingTo("37.4800");
        assertThat(processedPoints.get(1).getLatitude()).isEqualByComparingTo("37.4810");
    }

    private RideRecordPointEntity rawPoint(Long rideRecordId, int pointOrder, String latitude, String longitude) {
        return new RideRecordPointEntity(
                rideRecordId,
                pointOrder,
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00").plusSeconds(pointOrder),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
