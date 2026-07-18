package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record RideRecordListItemResponse(
        Long rideRecordId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Integer distanceM,
        Integer durationSec,
        String finalizationStatus,
        Long linkedCourseId,
        String qualityStatus,
        List<String> qualityReasons
) {
    public RideRecordListItemResponse(
            Long rideRecordId,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Integer distanceM,
            Integer durationSec,
            String finalizationStatus,
            Long linkedCourseId
    ) {
        this(rideRecordId, startedAt, endedAt, distanceM, durationSec, finalizationStatus,
                linkedCourseId, null, List.of());
    }
}
