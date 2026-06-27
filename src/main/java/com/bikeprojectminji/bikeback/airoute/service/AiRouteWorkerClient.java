package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import java.util.Optional;

public interface AiRouteWorkerClient {

    default String provider() {
        return "AI_ROUTE_WORKER";
    }

    default String fallbackReasonWhenEmpty() {
        return "AI_WORKER_UNAVAILABLE";
    }

    Optional<AiRoutePlanResponse> plan(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            AiRoutePlanResponse fallbackPlan
    );
}
