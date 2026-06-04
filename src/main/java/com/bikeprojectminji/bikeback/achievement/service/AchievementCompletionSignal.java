package com.bikeprojectminji.bikeback.achievement.service;

import java.util.List;

public record AchievementCompletionSignal(
        Long userId,
        Long courseId,
        Long rideRecordId,
        List<AchievementRoutePoint> routePoints
) {
}
