package com.bikeprojectminji.bikeback.routing.service;

import java.util.List;

public record BicycleRouteCandidate(
        String routeType,
        int distanceMeters,
        int durationSeconds,
        List<BicycleRoutePoint> polyline,
        String evidenceSummary,
        int bikePathScore,
        int sceneryScore,
        List<RouteEvidenceBadge> evidenceBadges
) {

    public BicycleRouteCandidate(
            String routeType,
            int distanceMeters,
            int durationSeconds,
            List<BicycleRoutePoint> polyline,
            String evidenceSummary,
            int bikePathScore,
            int sceneryScore
    ) {
        this(routeType, distanceMeters, durationSeconds, polyline, evidenceSummary, bikePathScore, sceneryScore, List.of());
    }

    public BicycleRouteCandidate {
        polyline = polyline == null ? List.of() : List.copyOf(polyline);
        evidenceBadges = evidenceBadges == null ? List.of() : List.copyOf(evidenceBadges);
    }
}
