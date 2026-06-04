package com.bikeprojectminji.bikeback.achievement.dto;

import java.util.List;

public record AchievementListResponse(
        List<AchievementItemResponse> achievements
) {
}
