package com.bikeprojectminji.bikeback.ride.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ride_records")
public class RideRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private OffsetDateTime endedAt;

    @Column(name = "distance_m", nullable = false)
    private Integer distanceM;

    @Column(name = "duration_sec", nullable = false)
    private Integer durationSec;

    @Column(name = "finalization_status", nullable = false)
    private String finalizationStatus;

    @Column(name = "finalization_attempts", nullable = false)
    private Integer finalizationAttempts;

    @Column(name = "finalization_started_at")
    private OffsetDateTime finalizationStartedAt;

    @Column(name = "finalization_completed_at")
    private OffsetDateTime finalizationCompletedAt;

    @Column(name = "finalization_failed_at")
    private OffsetDateTime finalizationFailedAt;

    @Column(name = "finalization_error_message")
    private String finalizationErrorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RideRecordEntity() {
    }

    public RideRecordEntity(Long ownerUserId, OffsetDateTime startedAt, OffsetDateTime endedAt, Integer distanceM, Integer durationSec) {
        this.ownerUserId = ownerUserId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.distanceM = distanceM;
        this.durationSec = durationSec;
        this.finalizationStatus = RideRecordFinalizationStatus.FINALIZING.name();
        this.finalizationAttempts = 0;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public Integer getDistanceM() {
        return distanceM;
    }

    public Integer getDurationSec() {
        return durationSec;
    }

    public RideRecordFinalizationStatus getFinalizationStatus() {
        return RideRecordFinalizationStatus.valueOf(finalizationStatus);
    }

    public Integer getFinalizationAttempts() {
        return finalizationAttempts;
    }

    public OffsetDateTime getFinalizationStartedAt() {
        return finalizationStartedAt;
    }

    public OffsetDateTime getFinalizationCompletedAt() {
        return finalizationCompletedAt;
    }

    public OffsetDateTime getFinalizationFailedAt() {
        return finalizationFailedAt;
    }

    public String getFinalizationErrorMessage() {
        return finalizationErrorMessage;
    }

    // 주행 기록 후처리는 raw 저장 직후 FINALIZING으로 시작하고,
    // 최종 경로가 준비되면 READY, 실패하면 FAILED로 전이한다.
    public void markFinalizing(OffsetDateTime now) {
        this.finalizationStatus = RideRecordFinalizationStatus.FINALIZING.name();
        this.finalizationAttempts = this.finalizationAttempts + 1;
        this.finalizationStartedAt = now;
        this.finalizationCompletedAt = null;
        this.finalizationFailedAt = null;
        this.finalizationErrorMessage = null;
    }

    public void markReady(OffsetDateTime now) {
        this.finalizationStatus = RideRecordFinalizationStatus.READY.name();
        this.finalizationCompletedAt = now;
        this.finalizationFailedAt = null;
        this.finalizationErrorMessage = null;
    }

    public void markFailed(OffsetDateTime now, String errorMessage) {
        this.finalizationStatus = RideRecordFinalizationStatus.FAILED.name();
        this.finalizationFailedAt = now;
        this.finalizationErrorMessage = errorMessage;
    }
}
