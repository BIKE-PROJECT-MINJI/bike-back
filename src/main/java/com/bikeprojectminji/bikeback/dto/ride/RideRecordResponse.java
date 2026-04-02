package com.bikeprojectminji.bikeback.dto.ride;

public record RideRecordResponse(
        Long rideRecordId,
        Long ownerUserId,
        Integer routePointCount
) {
}
