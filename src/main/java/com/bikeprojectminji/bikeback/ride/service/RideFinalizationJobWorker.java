package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class RideFinalizationJobWorker {

    private static final Logger log = LoggerFactory.getLogger(RideFinalizationJobWorker.class);

    private final RideFinalizationJobService rideFinalizationJobService;
    private final RideRecordFinalizationWriter rideRecordFinalizationWriter;
    private final RideRecordFinalizationFailureService rideRecordFinalizationFailureService;
    private final Clock clock;
    private final String appRole;
    private final String workerId;
    private final int batchSize;

    public RideFinalizationJobWorker(
            RideFinalizationJobService rideFinalizationJobService,
            RideRecordFinalizationWriter rideRecordFinalizationWriter,
            RideRecordFinalizationFailureService rideRecordFinalizationFailureService,
            Clock clock,
            @Value("${bike.app-role:all}") String appRole,
            @Value("${ride.finalization.worker.id:}") String configuredWorkerId,
            @Value("${ride.finalization.worker.batch-size:5}") int batchSize
    ) {
        this.rideFinalizationJobService = rideFinalizationJobService;
        this.rideRecordFinalizationWriter = rideRecordFinalizationWriter;
        this.rideRecordFinalizationFailureService = rideRecordFinalizationFailureService;
        this.clock = clock;
        this.appRole = appRole == null ? "all" : appRole.trim().toLowerCase();
        this.workerId = configuredWorkerId == null || configuredWorkerId.isBlank()
                ? "worker-" + UUID.randomUUID()
                : configuredWorkerId.trim();
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${ride.finalization.worker.poll-delay-ms:500}")
    public void processDueJobs() {
        if (!workerEnabled()) {
            return;
        }
        for (int i = 0; i < batchSize; i++) {
            Optional<RideFinalizationJobLease> lease = rideFinalizationJobService.acquireNext(workerId);
            if (lease.isEmpty()) {
                return;
            }
            process(lease.get());
        }
    }

    private boolean workerEnabled() {
        return "all".equals(appRole) || "worker".equals(appRole);
    }

    private void process(RideFinalizationJobLease lease) {
        String requestId = "finalization-job-" + lease.jobId();
        RequestLogContext.bind(requestId, requestId);
        OffsetDateTime startedAt = OffsetDateTime.now(clock);
        try {
            rideRecordFinalizationWriter.replaceProcessedPoints(lease.rideRecordId());
            boolean marked = rideFinalizationJobService.markSucceeded(
                    lease.jobId(),
                    workerId,
                    lease.attemptCount(),
                    Duration.between(startedAt, OffsetDateTime.now(clock))
            );
            if (!marked) {
                log.warn(
                        "ride_finalization_job_stale_completion request_id={} trace_id={} job_id={} ride_record_id={} attempt={}",
                        RequestLogContext.currentRequestId(),
                        RequestLogContext.currentTraceId(),
                        lease.jobId(),
                        lease.rideRecordId(),
                        lease.attemptCount()
                );
                return;
            }
            log.info(
                    "ride_finalization_job_succeeded request_id={} trace_id={} job_id={} ride_record_id={} attempt={}",
                    RequestLogContext.currentRequestId(),
                    RequestLogContext.currentTraceId(),
                    lease.jobId(),
                    lease.rideRecordId(),
                    lease.attemptCount()
            );
        } catch (Exception exception) {
            Duration duration = Duration.between(startedAt, OffsetDateTime.now(clock));
            boolean terminal = rideFinalizationJobService.markFailedOrRetry(
                    lease.jobId(),
                    workerId,
                    lease.attemptCount(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage(),
                    duration
            );
            if (terminal) {
                rideRecordFinalizationFailureService.markFailed(lease.rideRecordId(), exception.getMessage());
            }
            log.warn(
                    "ride_finalization_job_failed request_id={} trace_id={} job_id={} ride_record_id={} attempt={} terminal={} error_code={}",
                    RequestLogContext.currentRequestId(),
                    RequestLogContext.currentTraceId(),
                    lease.jobId(),
                    lease.rideRecordId(),
                    lease.attemptCount(),
                    terminal,
                    exception.getClass().getSimpleName()
            );
        } finally {
            RequestLogContext.clear();
        }
    }
}
