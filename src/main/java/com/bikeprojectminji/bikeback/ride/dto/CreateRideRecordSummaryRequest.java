package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;

public record CreateRideRecordSummaryRequest(
        String clientRideId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        RideRecordSummaryRequest summary
) {
}
