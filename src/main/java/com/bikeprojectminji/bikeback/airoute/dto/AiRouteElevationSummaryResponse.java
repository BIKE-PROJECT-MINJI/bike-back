package com.bikeprojectminji.bikeback.airoute.dto;

import java.math.BigDecimal;

public record AiRouteElevationSummaryResponse(
        BigDecimal totalAscentM,
        BigDecimal totalDescentM,
        BigDecimal minAltitudeM,
        BigDecimal maxAltitudeM,
        BigDecimal maxSlopePercent,
        BigDecimal averageSlopePercent
) {
}
