package com.bikeprojectminji.bikeback.airoute.dto;

import java.math.BigDecimal;

public record AiRouteTextPlanRequest(
        BigDecimal lat,
        BigDecimal lon,
        String text
) {
}
