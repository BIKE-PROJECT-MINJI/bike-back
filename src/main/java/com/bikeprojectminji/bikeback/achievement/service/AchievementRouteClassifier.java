package com.bikeprojectminji.bikeback.achievement.service;

import java.math.BigDecimal;
import java.util.List;

class AchievementRouteClassifier {

    boolean isRiversideOrBikeRoad(List<AchievementRoutePoint> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            return false;
        }
        return routePoints.stream().anyMatch(this::isHanRiverCorridor);
    }

    private boolean isHanRiverCorridor(AchievementRoutePoint point) {
        if (point.latitude() == null || point.longitude() == null) {
            return false;
        }
        return between(point.latitude(), "37.50", "37.60") && between(point.longitude(), "126.90", "127.10");
    }

    private boolean between(BigDecimal value, String min, String max) {
        return value.compareTo(new BigDecimal(min)) >= 0 && value.compareTo(new BigDecimal(max)) <= 0;
    }
}
