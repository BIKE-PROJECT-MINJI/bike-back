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
@Table(name = "course_reports")
public class CourseReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 60)
    private CourseReportReason reason;

    @Column(name = "reported_at", nullable = false)
    private OffsetDateTime reportedAt;

    protected CourseReportEntity() {
    }

    private CourseReportEntity(Long courseId, Long reporterUserId, CourseReportReason reason, Clock clock) {
        if (courseId == null || courseId <= 0) {
            throw new IllegalArgumentException("courseId는 양수여야 합니다.");
        }
        if (reporterUserId == null || reporterUserId <= 0) {
            throw new IllegalArgumentException("reporterUserId는 양수여야 합니다.");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason은 비어 있을 수 없습니다.");
        }
        this.courseId = courseId;
        this.reporterUserId = reporterUserId;
        this.reason = reason;
        this.reportedAt = OffsetDateTime.now(clock);
    }

    public static CourseReportEntity create(Long courseId, Long reporterUserId, CourseReportReason reason, Clock clock) {
        return new CourseReportEntity(courseId, reporterUserId, reason, clock);
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getReporterUserId() {
        return reporterUserId;
    }

    public CourseReportReason getReason() {
        return reason;
    }

    public OffsetDateTime getReportedAt() {
        return reportedAt;
    }
}
