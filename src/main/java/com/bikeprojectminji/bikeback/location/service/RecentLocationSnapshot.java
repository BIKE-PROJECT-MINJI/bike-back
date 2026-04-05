package com.bikeprojectminji.bikeback.location.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RecentLocationSnapshot(
        Long rideRecordId,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer pointOrder,
        RecentLocationStatus status,
        OffsetDateTime capturedAt
) {
}
