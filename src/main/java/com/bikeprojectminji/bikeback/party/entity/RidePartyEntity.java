package com.bikeprojectminji.bikeback.party.entity;

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
@Table(name = "ride_parties")
public class RidePartyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(name = "scheduled_start_at")
    private OffsetDateTime scheduledStartAt;

    @Column(nullable = false)
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RidePartyStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected RidePartyEntity() {
    }

    public RidePartyEntity(Long courseId, Long hostUserId, String title, OffsetDateTime scheduledStartAt, Integer capacity) {
        this.courseId = courseId;
        this.hostUserId = hostUserId;
        this.title = title;
        this.scheduledStartAt = scheduledStartAt;
        this.capacity = capacity;
        this.status = RidePartyStatus.OPEN;
    }

    public void cancelByReport() {
        this.status = RidePartyStatus.CANCELED;
    }

    public void startRiding() {
        if (status == RidePartyStatus.OPEN) {
            status = RidePartyStatus.RIDING;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getHostUserId() {
        return hostUserId;
    }

    public String getTitle() {
        return title;
    }

    public OffsetDateTime getScheduledStartAt() {
        return scheduledStartAt;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public RidePartyStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
