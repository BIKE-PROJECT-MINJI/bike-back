package com.bikeprojectminji.bikeback.course.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

@Entity
@Table(name = "courses")
public class CourseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "distance_km", nullable = false, precision = 5, scale = 1)
    private BigDecimal distanceKm;

    @Column(name = "estimated_duration_min", nullable = false)
    private Integer estimatedDurationMin;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private boolean curated;

    @Column(name = "featured_rank")
    private Integer featuredRank;

    @Column(name = "start_latitude", precision = 10, scale = 7)
    private BigDecimal startLatitude;

    @Column(name = "start_longitude", precision = 10, scale = 7)
    private BigDecimal startLongitude;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "source_ride_record_id")
    private Long sourceRideRecordId;

    @Column(name = "source_ai_route_session_id")
    private Long sourceAiRouteSessionId;

    @Column(name = "source_ai_route_candidate_id")
    private Long sourceAiRouteCandidateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CourseVisibility visibility;

    @Column(name = "share_token", length = 64)
    private String shareToken;

    @Column(name = "report_hidden", nullable = false)
    private boolean reportHidden;

    @Column(name = "report_hidden_reason", length = 60)
    private String reportHiddenReason;

    @Column(name = "report_hidden_at")
    private OffsetDateTime reportHiddenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected CourseEntity() {
    }

    public CourseEntity(String title, BigDecimal distanceKm, Integer estimatedDurationMin, Integer displayOrder) {
        this(title, null, distanceKm, estimatedDurationMin, displayOrder, false, null, null, null, null, CourseVisibility.PUBLIC);
    }

    public CourseEntity(
            String title,
            BigDecimal distanceKm,
            Integer estimatedDurationMin,
            Integer displayOrder,
            boolean curated,
            Integer featuredRank,
            BigDecimal startLatitude,
            BigDecimal startLongitude,
            Long ownerUserId
    ) {
        this(title, null, distanceKm, estimatedDurationMin, displayOrder, curated, featuredRank, startLatitude, startLongitude, ownerUserId, CourseVisibility.PUBLIC);
    }

    public CourseEntity(
            String title,
            String description,
            BigDecimal distanceKm,
            Integer estimatedDurationMin,
            Integer displayOrder,
            boolean curated,
            Integer featuredRank,
            BigDecimal startLatitude,
            BigDecimal startLongitude,
            Long ownerUserId,
            CourseVisibility visibility
    ) {
        this(title, description, distanceKm, estimatedDurationMin, displayOrder, curated, featuredRank, startLatitude, startLongitude, ownerUserId, null, visibility);
    }

    public CourseEntity(
            String title,
            String description,
            BigDecimal distanceKm,
            Integer estimatedDurationMin,
            Integer displayOrder,
            boolean curated,
            Integer featuredRank,
            BigDecimal startLatitude,
            BigDecimal startLongitude,
            Long ownerUserId,
            Long sourceRideRecordId,
            CourseVisibility visibility
    ) {
        this.title = title;
        this.description = description;
        this.distanceKm = distanceKm;
        this.estimatedDurationMin = estimatedDurationMin;
        this.displayOrder = displayOrder;
        this.curated = curated;
        this.featuredRank = featuredRank;
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
        this.ownerUserId = ownerUserId;
        this.sourceRideRecordId = sourceRideRecordId;
        this.visibility = visibility;
    }

    public CourseEntity(
            String title,
            BigDecimal distanceKm,
            Integer estimatedDurationMin,
            Integer displayOrder,
            boolean curated,
            Integer featuredRank,
            BigDecimal startLatitude,
            BigDecimal startLongitude
    ) {
        this(title, null, distanceKm, estimatedDurationMin, displayOrder, curated, featuredRank, startLatitude, startLongitude, null, CourseVisibility.PUBLIC);
    }

    public void updateMetadata(String title, String description, CourseVisibility visibility) {
        this.title = title;
        this.description = description;
        this.visibility = visibility;
    }

    public void updateStartCoordinates(BigDecimal startLatitude, BigDecimal startLongitude) {
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
    }

    public void updateShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public void attachAiRouteSource(Long sourceAiRouteSessionId, Long sourceAiRouteCandidateId) {
        this.sourceAiRouteSessionId = sourceAiRouteSessionId;
        this.sourceAiRouteCandidateId = sourceAiRouteCandidateId;
    }

    public void hideByReport(String reportHiddenReason, Clock clock) {
        if (reportHiddenReason == null || reportHiddenReason.isBlank()) {
            throw new IllegalArgumentException("reportHiddenReason은 비어 있을 수 없습니다.");
        }
        this.reportHidden = true;
        this.reportHiddenReason = reportHiddenReason;
        this.reportHiddenAt = OffsetDateTime.now(clock);
    }

    public void anonymizeOwner() {
        this.ownerUserId = null;
        this.sourceRideRecordId = null;
        this.sourceAiRouteSessionId = null;
        this.sourceAiRouteCandidateId = null;
        this.shareToken = null;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public String getDescription() {
        return description;
    }

    public Integer getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public boolean isCurated() {
        return curated;
    }

    public Integer getFeaturedRank() {
        return featuredRank;
    }

    public BigDecimal getStartLatitude() {
        return startLatitude;
    }

    public BigDecimal getStartLongitude() {
        return startLongitude;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public Long getSourceRideRecordId() {
        return sourceRideRecordId;
    }

    public Long getSourceAiRouteSessionId() {
        return sourceAiRouteSessionId;
    }

    public Long getSourceAiRouteCandidateId() {
        return sourceAiRouteCandidateId;
    }

    public CourseVisibility getVisibility() {
        return visibility;
    }

    public String getShareToken() {
        return shareToken;
    }

    public boolean isReportHidden() {
        return reportHidden;
    }

    public String getReportHiddenReason() {
        return reportHiddenReason;
    }

    public OffsetDateTime getReportHiddenAt() {
        return reportHiddenAt;
    }
}
