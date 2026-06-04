package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateRideRecordRequest(
        String clientRideId,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        RideRecordSummaryRequest summary,
        List<RideRecordPointRequest> routePoints
) {

    public CreateRideRecordRequest(
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            RideRecordSummaryRequest summary,
            List<RideRecordPointRequest> routePoints
    ) {
        this(null, startedAt, endedAt, summary, routePoints);
    }
}
