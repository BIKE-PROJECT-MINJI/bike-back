package com.bikeprojectminji.bikeback.ride.service;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RideRecordFinalizationProcessorTest {

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    @Mock
    private RideRecordProcessedPointRepository rideRecordProcessedPointRepository;

    @Mock
    private RideRouteCanonicalizer rideRouteCanonicalizer;

    @Mock
    private RideRecordFinalizationWriter rideRecordFinalizationWriter;

    @Mock
    private RideRecordFinalizationFailureService rideRecordFinalizationFailureService;

    @Test
    @DisplayName("이미 READY인 주행 기록 finalization 요청은 processed point를 다시 쓰지 않는다")
    void replaceProcessedPointsSkipsReadyRideRecord() {
        RideRecordEntity rideRecord = readyRideRecord();
        given(rideRecordRepository.findByIdForUpdate(7L)).willReturn(Optional.of(rideRecord));

        writer().replaceProcessedPoints(7L);

        verify(rideRecordPointRepository, never()).findByRideRecordIdOrderByPointOrderAsc(7L);
        verify(rideRecordProcessedPointRepository, never()).deleteByRideRecordId(7L);
        verify(rideRecordProcessedPointRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("FINALIZING 주행 기록은 잠금 조회 후 기존 processed point 삭제를 flush하고 다시 저장한다")
    void replaceProcessedPointsLocksAndFlushesBeforeSavingProcessedPoints() {
        RideRecordEntity rideRecord = finalizingRideRecord();
        given(rideRecordRepository.findByIdForUpdate(7L)).willReturn(Optional.of(rideRecord));
        given(rideRecordPointRepository.findByRideRecordIdOrderByPointOrderAsc(7L)).willReturn(List.of(
                rawPoint(7L, 1, "37.4812", "126.9527"),
                rawPoint(7L, 2, "37.4824", "126.9553")
        ));
        given(rideRouteCanonicalizer.canonicalize(anyList())).willReturn(List.of(
                new RideRecordPointRequest(1, new BigDecimal("37.4812"), new BigDecimal("126.9527")),
                new RideRecordPointRequest(2, new BigDecimal("37.4824"), new BigDecimal("126.9553"))
        ));

        writer().replaceProcessedPoints(7L);

        InOrder inOrder = inOrder(rideRecordRepository, rideRecordProcessedPointRepository);
        inOrder.verify(rideRecordRepository).findByIdForUpdate(7L);
        inOrder.verify(rideRecordProcessedPointRepository).deleteByRideRecordId(7L);
        inOrder.verify(rideRecordProcessedPointRepository).flush();
        inOrder.verify(rideRecordProcessedPointRepository).saveAll(anyList());
        verify(rideRecordRepository).save(rideRecord);
    }

    @Test
    @DisplayName("processed point 교체 실패는 별도 실패 상태 기록 서비스로 넘긴다")
    void finalizeRideRecordMarksFailedInSeparateServiceWhenReplacementFails() {
        RuntimeException failure = new RuntimeException("canonicalization failed");
        doThrow(failure).when(rideRecordFinalizationWriter).replaceProcessedPoints(7L);

        processor().finalizeRideRecord(7L);

        then(rideRecordFinalizationFailureService).should().markFailed(7L, "canonicalization failed");
    }

    private RideRecordFinalizationWriter writer() {
        return new RideRecordFinalizationWriter(
                rideRecordRepository,
                rideRecordPointRepository,
                rideRecordProcessedPointRepository,
                rideRouteCanonicalizer
        );
    }

    private RideRecordFinalizationProcessor processor() {
        return new RideRecordFinalizationProcessor(
                rideRecordFinalizationWriter,
                rideRecordFinalizationFailureService,
                new BikeMetricsRecorder(new SimpleMeterRegistry())
        );
    }

    private RideRecordEntity finalizingRideRecord() {
        RideRecordEntity rideRecord = new RideRecordEntity(
                1L,
                OffsetDateTime.parse("2026-04-21T10:00:00+09:00"),
                OffsetDateTime.parse("2026-04-21T11:00:00+09:00"),
                820,
                90
        );
        ReflectionTestUtils.setField(rideRecord, "id", 7L);
        return rideRecord;
    }

    private RideRecordEntity readyRideRecord() {
        RideRecordEntity rideRecord = finalizingRideRecord();
        rideRecord.markReady(OffsetDateTime.parse("2026-04-21T11:01:00+09:00"));
        return rideRecord;
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
