package com.bikeprojectminji.bikeback.airoute.dto;

import java.math.BigDecimal;

public record AiRoutePointResponse(
        BigDecimal lat,
        BigDecimal lon,
        String label,
        BigDecimal altitudeM
) {

    public AiRoutePointResponse(BigDecimal lat, BigDecimal lon, String label) {
        this(lat, lon, label, null);
    }
}
