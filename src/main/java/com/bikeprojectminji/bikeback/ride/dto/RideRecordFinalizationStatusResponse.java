package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;
import java.util.List;

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
        Long linkedCourseId,
        String qualityStatus,
        List<String> qualityReasons
) {

    public RideRecordFinalizationStatusResponse(
            Long rideRecordId,
            String status,
            Integer rawPointCount,
            Integer processedPointCount,
            Integer finalizationAttempts,
            String errorMessage
    ) {
        this(rideRecordId, status, rawPointCount, processedPointCount, finalizationAttempts, errorMessage,
                null, null, null, null, null, null, List.of());
    }

    public RideRecordFinalizationStatusResponse(
            Long rideRecordId,
            String status,
            Integer rawPointCount,
            Integer processedPointCount,
            Integer finalizationAttempts,
            String errorMessage,
            String qualityStatus,
            List<String> qualityReasons
    ) {
        this(rideRecordId, status, rawPointCount, processedPointCount, finalizationAttempts, errorMessage,
                null, null, null, null, null, qualityStatus, qualityReasons);
    }

    public RideRecordFinalizationStatusResponse(
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
        this(rideRecordId, status, rawPointCount, processedPointCount, finalizationAttempts, errorMessage,
                startedAt, endedAt, distanceM, durationSec, linkedCourseId, null, List.of());
    }
}
