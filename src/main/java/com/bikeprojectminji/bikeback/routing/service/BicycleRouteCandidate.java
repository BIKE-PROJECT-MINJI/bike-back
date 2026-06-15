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
        List<RouteEvidenceBadge> evidenceBadges,
        ElevationSummary elevationSummary
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
        this(routeType, distanceMeters, durationSeconds, polyline, evidenceSummary, bikePathScore, sceneryScore, List.of(), ElevationSummary.unknown());
    }

    public BicycleRouteCandidate(
            String routeType,
            int distanceMeters,
            int durationSeconds,
            List<BicycleRoutePoint> polyline,
            String evidenceSummary,
            int bikePathScore,
            int sceneryScore,
            List<RouteEvidenceBadge> evidenceBadges
    ) {
        this(routeType, distanceMeters, durationSeconds, polyline, evidenceSummary, bikePathScore, sceneryScore, evidenceBadges, ElevationSummary.unknown());
    }

    public BicycleRouteCandidate {
        polyline = polyline == null ? List.of() : List.copyOf(polyline);
        evidenceBadges = evidenceBadges == null ? List.of() : List.copyOf(evidenceBadges);
        elevationSummary = elevationSummary == null ? ElevationSummary.unknown() : elevationSummary;
    }
}
