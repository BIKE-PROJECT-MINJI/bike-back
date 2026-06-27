package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DisabledAiRouteWorkerClient implements AiRouteWorkerClient {

    @Override
    public String provider() {
        return "DISABLED_AI_ROUTE_WORKER";
    }

    @Override
    public Optional<AiRoutePlanResponse> plan(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            AiRoutePlanResponse fallbackPlan
    ) {
        return Optional.empty();
    }
}
