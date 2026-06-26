package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import com.bikeprojectminji.bikeback.ride.entity.RideFinalizationJobEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideFinalizationJobStatus;
import com.bikeprojectminji.bikeback.ride.repository.RideFinalizationJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideFinalizationJobService {

    private static final List<RideFinalizationJobStatus> RUNNABLE_STATUSES = List.of(
            RideFinalizationJobStatus.PENDING,
            RideFinalizationJobStatus.RETRY_WAIT
    );

    private final RideFinalizationJobRepository rideFinalizationJobRepository;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration retryBaseDelay;

    public RideFinalizationJobService(
            RideFinalizationJobRepository rideFinalizationJobRepository,
            BikeMetricsRecorder bikeMetricsRecorder,
            Clock clock,
            @Value("${ride.finalization.worker.lease-sec:60}") long leaseSec,
            @Value("${ride.finalization.worker.retry-base-delay-sec:5}") long retryBaseDelaySec
    ) {
        this.rideFinalizationJobRepository = rideFinalizationJobRepository;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
        this.clock = clock;
        this.leaseDuration = Duration.ofSeconds(leaseSec);
        this.retryBaseDelay = Duration.ofSeconds(retryBaseDelaySec);
    }

    @MeasuredOperation("ride.finalization.job.enqueue")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(Long rideRecordId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        RideFinalizationJobEntity job = rideFinalizationJobRepository.findByRideRecordId(rideRecordId)
                .orElseGet(() -> new RideFinalizationJobEntity(rideRecordId, now));
        job.enqueue(now);
        rideFinalizationJobRepository.save(job);
    }

    @MeasuredOperation("ride.finalization.job.acquire")
    @Transactional
    public Optional<RideFinalizationJobLease> acquireNext(String workerId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RideFinalizationJobEntity> runnableJobs = rideFinalizationJobRepository.findRunnableJobs(
                RUNNABLE_STATUSES,
                now,
                PageRequest.of(0, 1)
        );
        if (runnableJobs.isEmpty()) {
            runnableJobs = rideFinalizationJobRepository.findExpiredRunningJobs(now, PageRequest.of(0, 1));
        }
        if (runnableJobs.isEmpty()) {
            return Optional.empty();
        }

        RideFinalizationJobEntity job = runnableJobs.get(0);
        job.markRunning(workerId, now, now.plus(leaseDuration));
        rideFinalizationJobRepository.save(job);
        bikeMetricsRecorder.recordRideFinalizationJobAcquired();
        return Optional.of(new RideFinalizationJobLease(job.getId(), job.getRideRecordId(), job.getAttemptCount()));
    }

    @MeasuredOperation("ride.finalization.job.succeed")
    @Transactional
    public boolean markSucceeded(Long jobId, String workerId, int attemptCount, Duration duration) {
        RideFinalizationJobEntity job = rideFinalizationJobRepository.findById(jobId).orElseThrow();
        if (!job.isOwnedRunningLease(workerId, attemptCount)) {
            return false;
        }
        job.markSucceeded(OffsetDateTime.now(clock));
        rideFinalizationJobRepository.save(job);
        bikeMetricsRecorder.recordRideFinalizationJobDuration("succeeded", duration);
        return true;
    }

    @MeasuredOperation("ride.finalization.job.fail_or_retry")
    @Transactional
    public boolean markFailedOrRetry(
            Long jobId,
            String workerId,
            int attemptCount,
            String errorCode,
            String errorMessage,
            Duration duration
    ) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        RideFinalizationJobEntity job = rideFinalizationJobRepository.findById(jobId).orElseThrow();
        if (!job.isOwnedRunningLease(workerId, attemptCount)) {
            return false;
        }
        String sanitizedErrorCode = sanitizeErrorCode(errorCode);
        String sanitizedMessage = sanitizeErrorMessage(errorMessage);
        if (job.attemptsExhausted()) {
            job.markFailed(now, sanitizedErrorCode, sanitizedMessage);
            rideFinalizationJobRepository.save(job);
            bikeMetricsRecorder.recordRideRecordFinalizationFailure();
            bikeMetricsRecorder.recordRideFinalizationJobTerminalFailure(sanitizedErrorCode);
            bikeMetricsRecorder.recordRideFinalizationJobDuration("failed", duration);
            return true;
        }

        OffsetDateTime nextRunAt = now.plus(retryBaseDelay.multipliedBy(Math.max(1, job.getAttemptCount())));
        job.markRetryWait(now, nextRunAt, sanitizedErrorCode, sanitizedMessage);
        rideFinalizationJobRepository.save(job);
        bikeMetricsRecorder.recordRideFinalizationJobRetry(sanitizedErrorCode);
        bikeMetricsRecorder.recordRideFinalizationJobDuration("retry_wait", duration);
        return false;
    }

    private String sanitizeErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "unknown";
        }
        return errorCode.trim().toLowerCase().replaceAll("[^a-z0-9_.:-]", "_");
    }

    private String sanitizeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "unknown";
        }
        String singleLine = errorMessage.replace('\n', ' ').replace('\r', ' ').trim();
        if (singleLine.length() > 300) {
            return singleLine.substring(0, 300);
        }
        return singleLine;
    }
}
