package com.bikeprojectminji.bikeback.profile.dto;

import java.math.BigDecimal;

public record ProfileOverallActivitySummaryResponse(
        BigDecimal totalDistanceKm,
        long totalRides,
        BigDecimal avgSpeedKmh,
        long totalElevationM
) {
}
