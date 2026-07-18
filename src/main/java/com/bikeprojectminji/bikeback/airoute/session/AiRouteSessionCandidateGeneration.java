package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import java.util.List;

record AiRouteSessionCandidateGeneration(
        List<AiRoutePlanResponse> plans,
        int attemptCount,
        int noRouteCount,
        int providerUnavailableCount,
        int quotaExceededCount,
        int duplicateCount
) {

    boolean partial() {
        return plans.size() < AiRouteSessionCandidateGenerator.MAX_CANDIDATES;
    }

    String fallbackReason() {
        if (!partial()) {
            return null;
        }
        return "PARTIAL_CANDIDATES;NO_ROUTE=" + noRouteCount
                + ";PROVIDER_UNAVAILABLE=" + providerUnavailableCount
                + ";QUOTA_EXCEEDED=" + quotaExceededCount
                + ";DUPLICATE=" + duplicateCount;
    }
}
