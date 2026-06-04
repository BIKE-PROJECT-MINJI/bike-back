package com.bikeprojectminji.bikeback.routing.service;

import java.util.List;

public record BicycleRoutePlan(
        String status,
        String provider,
        boolean fallbackUsed,
        String message,
        List<BicycleRouteCandidate> candidates
) {
}
