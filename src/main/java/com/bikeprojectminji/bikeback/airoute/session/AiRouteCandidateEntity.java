package com.bikeprojectminji.bikeback.airoute.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_route_candidates")
public class AiRouteCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(length = 1000)
    private String summary;

    @Column(name = "distance_km", nullable = false, precision = 5, scale = 1)
    private BigDecimal distanceKm;

    @Column(name = "estimated_duration_min", nullable = false)
    private Integer estimatedDurationMin;

    @Column(name = "recommendation_score", nullable = false)
    private Integer recommendationScore;

    @Column(name = "elevation_summary_json", columnDefinition = "TEXT")
    private String elevationSummaryJson;

    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    @Column(name = "evidence_badges_json", columnDefinition = "TEXT")
    private String evidenceBadgesJson;

    @Column(name = "routing_metadata_json", columnDefinition = "TEXT")
    private String routingMetadataJson;

    @Column(name = "preference_summary", length = 500)
    private String preferenceSummary;

    @Column(name = "elevation_status", length = 32)
    private String elevationStatus;

    @Column(name = "scenery_evidence_status", length = 32)
    private String sceneryEvidenceStatus;

    @Column(name = "route_point_count", nullable = false)
    private Integer routePointCount;

    @Column(name = "route_points_json", nullable = false, columnDefinition = "TEXT")
    private String routePointsJson;

    @Column(name = "promoted_course_id")
    private Long promotedCourseId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AiRouteCandidateEntity() {
    }

    public AiRouteCandidateEntity(
            Long sessionId,
            String title,
            String summary,
            BigDecimal distanceKm,
            Integer estimatedDurationMin,
            Integer recommendationScore,
            String elevationSummaryJson,
            String scoreBreakdownJson,
            String evidenceBadgesJson,
            String routingMetadataJson,
            String preferenceSummary,
            String elevationStatus,
            String sceneryEvidenceStatus,
            Integer routePointCount,
            String routePointsJson
    ) {
        this.sessionId = sessionId;
        this.title = title;
        this.summary = summary;
        this.distanceKm = distanceKm;
        this.estimatedDurationMin = estimatedDurationMin;
        this.recommendationScore = recommendationScore;
        this.elevationSummaryJson = elevationSummaryJson;
        this.scoreBreakdownJson = scoreBreakdownJson;
        this.evidenceBadgesJson = evidenceBadgesJson;
        this.routingMetadataJson = routingMetadataJson;
        this.preferenceSummary = preferenceSummary;
        this.elevationStatus = elevationStatus;
        this.sceneryEvidenceStatus = sceneryEvidenceStatus;
        this.routePointCount = routePointCount;
        this.routePointsJson = routePointsJson;
    }

    public void markPromoted(Long promotedCourseId) {
        this.promotedCourseId = promotedCourseId;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public Integer getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public Integer getRecommendationScore() {
        return recommendationScore;
    }

    public String getElevationSummaryJson() {
        return elevationSummaryJson;
    }

    public String getScoreBreakdownJson() {
        return scoreBreakdownJson;
    }

    public String getEvidenceBadgesJson() {
        return evidenceBadgesJson;
    }

    public String getRoutingMetadataJson() {
        return routingMetadataJson;
    }

    public String getPreferenceSummary() {
        return preferenceSummary;
    }

    public String getElevationStatus() {
        return elevationStatus;
    }

    public String getSceneryEvidenceStatus() {
        return sceneryEvidenceStatus;
    }

    public Integer getRoutePointCount() {
        return routePointCount;
    }

    public String getRoutePointsJson() {
        return routePointsJson;
    }

    public Long getPromotedCourseId() {
        return promotedCourseId;
    }
}
