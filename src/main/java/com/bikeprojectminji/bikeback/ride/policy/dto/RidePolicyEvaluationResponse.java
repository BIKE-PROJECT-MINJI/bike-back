package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyEvaluationResponse(
        String phase,
        RidePolicyGateResponse startGate,
        RidePolicyOffRouteResponse offRoute,
        RidePolicyCompletionResponse completion,
        String overallState,
        String defaultMessage
) {
}
