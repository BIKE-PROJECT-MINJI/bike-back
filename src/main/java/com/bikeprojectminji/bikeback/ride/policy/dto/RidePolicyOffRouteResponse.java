package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyOffRouteResponse(
        String status,
        String reasonCode,
        Integer distanceM,
        Integer candidateThresholdM,
        Integer warningThresholdSec,
        Integer recoveryThresholdM,
        Integer durationSec
) {

    public RidePolicyOffRouteResponse(String status, String reasonCode) {
        this(status, reasonCode, null, null, null, null, null);
    }
}
