package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyEvaluationRequest(
        String phase,
        RideLocationRequest location
) {
}
