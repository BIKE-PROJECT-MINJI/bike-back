package com.bikeprojectminji.bikeback.location.dto;

import com.bikeprojectminji.bikeback.location.service.RecentLocationStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RecentLocationResponse(
        Long rideRecordId,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer pointOrder,
        RecentLocationStatus status,
        OffsetDateTime capturedAt
) {
}
