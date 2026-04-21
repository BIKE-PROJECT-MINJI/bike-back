package com.bikeprojectminji.bikeback.ride.dto;

public record RideRecordFinalizationStatusResponse(
        Long rideRecordId,
        String status,
        Integer rawPointCount,
        Integer processedPointCount,
        Integer finalizationAttempts,
        String errorMessage
) {
}
