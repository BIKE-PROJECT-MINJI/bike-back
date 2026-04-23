package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;

public record RideRecordListItemResponse(
        Long rideRecordId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        Integer distanceM,
        Integer durationSec,
        String finalizationStatus,
        Long linkedCourseId
) {
}
