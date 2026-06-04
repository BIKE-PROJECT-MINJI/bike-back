package com.bikeprojectminji.bikeback.airoute.dto;

import java.math.BigDecimal;

public record AiRoutePointResponse(
        BigDecimal lat,
        BigDecimal lon,
        String label
) {
}
