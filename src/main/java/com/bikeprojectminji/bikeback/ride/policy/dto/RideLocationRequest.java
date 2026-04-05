package com.bikeprojectminji.bikeback.ride.policy.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RideLocationRequest(
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal accuracyM,
        OffsetDateTime capturedAt
) {
}
