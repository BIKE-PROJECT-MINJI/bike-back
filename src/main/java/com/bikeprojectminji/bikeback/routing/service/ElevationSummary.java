package com.bikeprojectminji.bikeback.routing.service;

import java.math.BigDecimal;

public record ElevationSummary(
        BigDecimal totalAscentM,
        BigDecimal totalDescentM,
        BigDecimal minAltitudeM,
        BigDecimal maxAltitudeM,
        BigDecimal maxSlopePercent,
        BigDecimal averageSlopePercent
) {

    public static ElevationSummary unknown() {
        return new ElevationSummary(null, null, null, null, null, null);
    }

    public boolean hasElevation() {
        return totalAscentM != null || totalDescentM != null || minAltitudeM != null || maxAltitudeM != null;
    }
}
