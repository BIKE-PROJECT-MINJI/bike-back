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
}
