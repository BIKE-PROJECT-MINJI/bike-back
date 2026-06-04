package com.bikeprojectminji.bikeback.profile.entity;

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
@Table(name = "user_preference")
public class UserPreferenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "scenic", nullable = false)
    private boolean scenic;

    @Enumerated(EnumType.STRING)
    @Column(name = "bike_road_priority", nullable = false, length = 20)
    private BikeRoadPriority bikeRoadPriority;

    @Column(name = "avoid_dust", nullable = false)
    private boolean avoidDust;

    @Column(name = "avoid_unsafe_surface", nullable = false)
    private boolean avoidUnsafeSurface;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UserPreferenceEntity() {
    }

    private UserPreferenceEntity(
            Long userId,
            boolean scenic,
            BikeRoadPriority bikeRoadPriority,
            boolean avoidDust,
            boolean avoidUnsafeSurface,
            Clock clock
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId는 양수여야 합니다.");
        }
        validateBikeRoadPriority(bikeRoadPriority);
        OffsetDateTime now = OffsetDateTime.now(clock);
        this.userId = userId;
        this.scenic = scenic;
        this.bikeRoadPriority = bikeRoadPriority;
        this.avoidDust = avoidDust;
        this.avoidUnsafeSurface = avoidUnsafeSurface;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static UserPreferenceEntity create(
            Long userId,
            boolean scenic,
            BikeRoadPriority bikeRoadPriority,
            boolean avoidDust,
            boolean avoidUnsafeSurface,
            Clock clock
    ) {
        return new UserPreferenceEntity(userId, scenic, bikeRoadPriority, avoidDust, avoidUnsafeSurface, clock);
    }

    public void update(
            boolean scenic,
            BikeRoadPriority bikeRoadPriority,
            boolean avoidDust,
            boolean avoidUnsafeSurface,
            Clock clock
    ) {
        validateBikeRoadPriority(bikeRoadPriority);
        this.scenic = scenic;
        this.bikeRoadPriority = bikeRoadPriority;
        this.avoidDust = avoidDust;
        this.avoidUnsafeSurface = avoidUnsafeSurface;
        this.updatedAt = OffsetDateTime.now(clock);
    }

    private static void validateBikeRoadPriority(BikeRoadPriority bikeRoadPriority) {
        if (bikeRoadPriority == null) {
            throw new IllegalArgumentException("bikeRoadPriority는 비어 있을 수 없습니다.");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isScenic() {
        return scenic;
    }

    public BikeRoadPriority getBikeRoadPriority() {
        return bikeRoadPriority;
    }

    public boolean isAvoidDust() {
        return avoidDust;
    }

    public boolean isAvoidUnsafeSurface() {
        return avoidUnsafeSurface;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
