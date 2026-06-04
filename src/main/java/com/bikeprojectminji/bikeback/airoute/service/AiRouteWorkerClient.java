package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import java.util.Optional;

public interface AiRouteWorkerClient {

    Optional<AiRoutePlanResponse> plan(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            AiRoutePlanResponse fallbackPlan
    );
}
