package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyEvaluationResponse(
        String phase,
        RidePolicyGateResponse startGate,
        RidePolicyOffRouteResponse offRoute,
        RidePolicyCompletionResponse completion,
        RidePolicyProgressResponse progress,
        String overallState,
        String defaultMessage
) {

    public RidePolicyEvaluationResponse(
            String phase,
            RidePolicyGateResponse startGate,
            RidePolicyOffRouteResponse offRoute,
            RidePolicyCompletionResponse completion,
            String overallState,
            String defaultMessage
    ) {
        this(phase, startGate, offRoute, completion, null, overallState, defaultMessage);
    }
}
