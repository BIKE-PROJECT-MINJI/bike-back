package com.bikeprojectminji.bikeback.achievement.dto;

import com.bikeprojectminji.bikeback.achievement.entity.AchievementType;
import java.time.OffsetDateTime;

public record AchievementItemResponse(
        AchievementType type,
        String title,
        String description,
        String sourceKey,
        Long sourceCourseId,
        Long sourceRideRecordId,
        OffsetDateTime grantedAt
) {
}
