package com.bikeprojectminji.bikeback.dto.ridepolicy;

public record RidePolicyEvaluationRequest(
        String phase,
        RideLocationRequest location
) {
}
