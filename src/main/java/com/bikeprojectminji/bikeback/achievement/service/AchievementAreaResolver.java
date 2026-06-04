package com.bikeprojectminji.bikeback.achievement.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

class AchievementAreaResolver {

    Optional<String> resolveFirstAreaCode(List<AchievementRoutePoint> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            return Optional.empty();
        }
        AchievementRoutePoint firstPoint = routePoints.get(0);
        if (isInside(firstPoint, "37.55", "37.59", "126.96", "127.02")) {
            return Optional.of("SEOUL_JUNG_GU");
        }
        if (isInside(firstPoint, "37.62", "37.68", "127.03", "127.08")) {
            return Optional.of("SEOUL_NOWON_GU");
        }
        return Optional.empty();
    }

    private boolean isInside(AchievementRoutePoint point, String minLat, String maxLat, String minLon, String maxLon) {
        if (point.latitude() == null || point.longitude() == null) {
            return false;
        }
        return between(point.latitude(), minLat, maxLat) && between(point.longitude(), minLon, maxLon);
    }

    private boolean between(BigDecimal value, String min, String max) {
        return value.compareTo(new BigDecimal(min)) >= 0 && value.compareTo(new BigDecimal(max)) <= 0;
    }
}
