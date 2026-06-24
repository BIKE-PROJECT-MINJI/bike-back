package com.bikeprojectminji.bikeback.course.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.OffsetDateTime;

@Entity
@Table(name = "course_publications")
public class CoursePublicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CoursePublicationStatus status;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt;

    @Column(name = "unpublished_at")
    private OffsetDateTime unpublishedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CoursePublicationEntity() {
    }

    public CoursePublicationEntity(Long courseId, Long ownerUserId, Clock clock) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.courseId = courseId;
        this.ownerUserId = ownerUserId;
        this.status = CoursePublicationStatus.ACTIVE;
        this.publishedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void publish(Clock clock) {
        if (status == CoursePublicationStatus.ACTIVE) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.status = CoursePublicationStatus.ACTIVE;
        this.publishedAt = now;
        this.unpublishedAt = null;
        this.updatedAt = now;
    }

    public void unpublish(Clock clock) {
        if (status == CoursePublicationStatus.INACTIVE) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.status = CoursePublicationStatus.INACTIVE;
        this.unpublishedAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public CoursePublicationStatus getStatus() {
        return status;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public OffsetDateTime getUnpublishedAt() {
        return unpublishedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
