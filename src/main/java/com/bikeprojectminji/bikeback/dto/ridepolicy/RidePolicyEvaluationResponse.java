package com.bikeprojectminji.bikeback.dto.ridepolicy;

public record RidePolicyEvaluationResponse(
        String phase,
        RidePolicyGateResponse startGate,
        RidePolicyGateResponse offRoute,
        String overallState,
        String defaultMessage
) {
}
