package com.bikeprojectminji.bikeback.ride.dto;

public record RideRecordResponse(
        Long rideRecordId,
        Long ownerUserId,
        Integer routePointCount,
        String finalizationStatus
) {
}
