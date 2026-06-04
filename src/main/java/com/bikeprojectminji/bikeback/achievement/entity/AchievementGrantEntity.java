package com.bikeprojectminji.bikeback.achievement.entity;

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
@Table(name = "achievement_grants")
public class AchievementGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "achievement_type", nullable = false, length = 60)
    private AchievementType achievementType;

    @Column(name = "source_key", nullable = false, length = 100)
    private String sourceKey;

    @Column(name = "source_course_id")
    private Long sourceCourseId;

    @Column(name = "source_ride_record_id")
    private Long sourceRideRecordId;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    protected AchievementGrantEntity() {
    }

    private AchievementGrantEntity(
            Long userId,
            AchievementType achievementType,
            String sourceKey,
            Long sourceCourseId,
            Long sourceRideRecordId,
            Clock clock
    ) {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 비어 있을 수 없습니다.");
        }
        if (achievementType == null) {
            throw new IllegalArgumentException("achievementType은 비어 있을 수 없습니다.");
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey는 비어 있을 수 없습니다.");
        }
        this.userId = userId;
        this.achievementType = achievementType;
        this.sourceKey = sourceKey;
        this.sourceCourseId = sourceCourseId;
        this.sourceRideRecordId = sourceRideRecordId;
        this.grantedAt = OffsetDateTime.now(clock);
    }

    public static AchievementGrantEntity create(
            Long userId,
            AchievementType achievementType,
            String sourceKey,
            Long sourceCourseId,
            Long sourceRideRecordId,
            Clock clock
    ) {
        return new AchievementGrantEntity(userId, achievementType, sourceKey, sourceCourseId, sourceRideRecordId, clock);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public AchievementType getAchievementType() {
        return achievementType;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public Long getSourceCourseId() {
        return sourceCourseId;
    }

    public Long getSourceRideRecordId() {
        return sourceRideRecordId;
    }

    public OffsetDateTime getGrantedAt() {
        return grantedAt;
    }
}
