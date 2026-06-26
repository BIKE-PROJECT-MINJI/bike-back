package com.bikeprojectminji.bikeback.ride.service;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RideFinalizationJobWorkerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-26T08:00:00Z"), ZoneOffset.UTC);

    @Mock
    private RideFinalizationJobService rideFinalizationJobService;

    @Mock
    private RideRecordFinalizationWriter rideRecordFinalizationWriter;

    @Mock
    private RideRecordFinalizationFailureService rideRecordFinalizationFailureService;

    @Test
    @DisplayName("app role이 api이면 finalization worker는 job을 가져오지 않는다")
    void apiRoleDoesNotProcessJobs() {
        RideFinalizationJobWorker worker = new RideFinalizationJobWorker(
                rideFinalizationJobService,
                rideRecordFinalizationWriter,
                rideRecordFinalizationFailureService,
                CLOCK,
                "api",
                "test-worker",
                1
        );

        worker.processDueJobs();

        then(rideFinalizationJobService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("terminal 실패가 아니면 ride record를 FAILED로 전이하지 않고 retry job만 남긴다")
    void retryableFailureDoesNotMarkRideRecordFailed() {
        RideFinalizationJobWorker worker = new RideFinalizationJobWorker(
                rideFinalizationJobService,
                rideRecordFinalizationWriter,
                rideRecordFinalizationFailureService,
                CLOCK,
                "worker",
                "test-worker",
                1
        );
        RideFinalizationJobLease lease = new RideFinalizationJobLease(11L, 22L, 1);
        given(rideFinalizationJobService.acquireNext("test-worker")).willReturn(Optional.of(lease));
        RuntimeException failure = new RuntimeException("canonicalization failed");
        org.mockito.Mockito.doThrow(failure).when(rideRecordFinalizationWriter).replaceProcessedPoints(22L);
        given(rideFinalizationJobService.markFailedOrRetry(
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq("test-worker"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        ))
                .willReturn(false);

        worker.processDueJobs();

        verify(rideRecordFinalizationFailureService, never()).markFailed(22L, "canonicalization failed");
    }
}
