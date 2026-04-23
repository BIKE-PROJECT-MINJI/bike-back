package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;

public record RideRecordFinalizationStatusResponse(
        Long rideRecordId,
        String status,
        Integer rawPointCount,
        Integer processedPointCount,
        Integer finalizationAttempts,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Integer distanceM,
        Integer durationSec,
        Long linkedCourseId
) {

    public RideRecordFinalizationStatusResponse(
            Long rideRecordId,
            String status,
            Integer rawPointCount,
            Integer processedPointCount,
            Integer finalizationAttempts,
            String errorMessage
    ) {
        this(rideRecordId, status, rawPointCount, processedPointCount, finalizationAttempts, errorMessage, null, null, null, null, null);
    }
}
