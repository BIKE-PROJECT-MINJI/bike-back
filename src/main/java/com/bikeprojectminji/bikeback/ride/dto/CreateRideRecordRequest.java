package com.bikeprojectminji.bikeback.ride.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateRideRecordRequest(
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        RideRecordSummaryRequest summary,
        List<RideRecordPointRequest> routePoints
) {
}
