package com.bikeprojectminji.bikeback.ride.policy.dto;

import java.util.List;

public record RidePolicyEvaluationRequest(
        String phase,
        RideLocationRequest location,
        List<RideLocationRequest> trace
) {

    public RidePolicyEvaluationRequest(String phase, RideLocationRequest location) {
        this(phase, location, List.of());
    }
}
