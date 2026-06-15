package com.bikeprojectminji.bikeback.ride.dto;

import java.util.List;

public record RideRecordTraceRequest(
        List<RideRecordPointRequest> routePoints
) {
}
