package com.bikeprojectminji.bikeback.ride.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RideRecordPointRequest(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude,
        OffsetDateTime capturedAt,
        BigDecimal accuracyM,
        BigDecimal speedMps,
        BigDecimal bearingDeg,
        BigDecimal altitudeM,
        BigDecimal distanceToRouteM,
        BigDecimal routeProgressPct
) {

    public RideRecordPointRequest(Integer pointOrder, BigDecimal latitude, BigDecimal longitude) {
        this(pointOrder, latitude, longitude, null, null, null, null, null, null, null);
    }
}
