package com.bikeprojectminji.bikeback.entity.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "courses")
public class CourseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

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

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected CourseEntity() {
    }

    public CourseEntity(String title, BigDecimal distanceKm, Integer estimatedDurationMin, Integer displayOrder) {
        this(title, distanceKm, estimatedDurationMin, displayOrder, false, null, null, null);
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
        this.title = title;
        this.distanceKm = distanceKm;
        this.estimatedDurationMin = estimatedDurationMin;
        this.displayOrder = displayOrder;
        this.curated = curated;
        this.featuredRank = featuredRank;
        this.startLatitude = startLatitude;
        this.startLongitude = startLongitude;
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
}
