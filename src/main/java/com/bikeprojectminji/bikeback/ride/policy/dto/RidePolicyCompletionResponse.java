package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyCompletionResponse(
        String status,
        String reasonCode,
        Integer coveragePercent,
        Integer coverageThresholdPercent,
        Boolean loopCourse,
        Boolean leftStartZone,
        Integer distanceM,
        Integer thresholdM
) {

    public RidePolicyCompletionResponse(String status, String reasonCode) {
        this(status, reasonCode, null, null, null, null, null, null);
    }
}
