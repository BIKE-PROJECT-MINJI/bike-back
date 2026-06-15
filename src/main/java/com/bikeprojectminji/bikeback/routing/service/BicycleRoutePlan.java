package com.bikeprojectminji.bikeback.routing.service;

import java.util.List;

public record BicycleRoutePlan(
        String status,
        String provider,
        boolean fallbackUsed,
        String message,
        List<BicycleRouteCandidate> candidates,
        String qualityStatus,
        String qualityMessage,
        String fallbackReason
) {

    public BicycleRoutePlan(
            String status,
            String provider,
            boolean fallbackUsed,
            String message,
            List<BicycleRouteCandidate> candidates
    ) {
        this(status, provider, fallbackUsed, message, candidates, "NOT_EVALUATED", "경로 품질 검증을 수행하지 않았습니다.", null);
    }
}
