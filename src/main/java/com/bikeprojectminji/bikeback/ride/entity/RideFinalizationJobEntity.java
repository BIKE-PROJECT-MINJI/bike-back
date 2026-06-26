package com.bikeprojectminji.bikeback.ride.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ride_finalization_jobs")
public class RideFinalizationJobEntity {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ride_record_id", nullable = false, unique = true)
    private Long rideRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RideFinalizationJobStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "next_run_at", nullable = false)
    private OffsetDateTime nextRunAt;

    @Column(name = "locked_by", length = 120)
    private String lockedBy;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_error_code", length = 80)
    private String lastErrorCode;

    @Column(name = "last_error_message")
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RideFinalizationJobEntity() {
    }

    public RideFinalizationJobEntity(Long rideRecordId, OffsetDateTime now) {
        this.rideRecordId = rideRecordId;
        this.status = RideFinalizationJobStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = DEFAULT_MAX_ATTEMPTS;
        this.nextRunAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getRideRecordId() {
        return rideRecordId;
    }

    public RideFinalizationJobStatus getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Integer getMaxAttempts() {
        return maxAttempts;
    }

    public OffsetDateTime getNextRunAt() {
        return nextRunAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void enqueue(OffsetDateTime now) {
        this.status = RideFinalizationJobStatus.PENDING;
        this.nextRunAt = now;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
        this.updatedAt = now;
    }

    public void markRunning(String workerId, OffsetDateTime now, OffsetDateTime lockedUntil) {
        this.status = RideFinalizationJobStatus.RUNNING;
        this.attemptCount = this.attemptCount + 1;
        this.lockedBy = workerId;
        this.lockedUntil = lockedUntil;
        this.updatedAt = now;
    }

    public void markSucceeded(OffsetDateTime now) {
        this.status = RideFinalizationJobStatus.SUCCEEDED;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public void markRetryWait(OffsetDateTime now, OffsetDateTime nextRunAt, String errorCode, String errorMessage) {
        this.status = RideFinalizationJobStatus.RETRY_WAIT;
        this.nextRunAt = nextRunAt;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        this.updatedAt = now;
    }

    public void markFailed(OffsetDateTime now, String errorCode, String errorMessage) {
        this.status = RideFinalizationJobStatus.FAILED;
        this.lockedBy = null;
        this.lockedUntil = null;
        this.lastErrorCode = errorCode;
        this.lastErrorMessage = errorMessage;
        this.updatedAt = now;
    }

    public boolean attemptsExhausted() {
        return attemptCount >= maxAttempts;
    }

    public boolean isOwnedRunningLease(String workerId, int expectedAttemptCount) {
        return status == RideFinalizationJobStatus.RUNNING
                && attemptCount == expectedAttemptCount
                && lockedBy != null
                && lockedBy.equals(workerId);
    }
}
