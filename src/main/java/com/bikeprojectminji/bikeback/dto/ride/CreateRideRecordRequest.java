package com.bikeprojectminji.bikeback.dto.ride;

import java.time.OffsetDateTime;
import java.util.List;

public record CreateRideRecordRequest(
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        RideRecordSummaryRequest summary,
        List<RideRecordPointRequest> routePoints
) {
}
