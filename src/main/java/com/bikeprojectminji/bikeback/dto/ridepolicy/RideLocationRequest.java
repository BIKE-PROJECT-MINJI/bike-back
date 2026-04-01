package com.bikeprojectminji.bikeback.dto.ridepolicy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RideLocationRequest(
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal accuracyM,
        OffsetDateTime capturedAt
) {
}
