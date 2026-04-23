package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyGateResponse(
        String status,
        String reasonCode,
        Integer distanceM,
        Integer thresholdM
) {

    public RidePolicyGateResponse(String status, String reasonCode) {
        this(status, reasonCode, null, null);
    }
}
