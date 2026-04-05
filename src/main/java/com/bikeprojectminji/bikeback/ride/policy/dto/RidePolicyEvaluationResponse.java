package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyEvaluationResponse(
        String phase,
        RidePolicyGateResponse startGate,
        RidePolicyGateResponse offRoute,
        String overallState,
        String defaultMessage
) {
}
