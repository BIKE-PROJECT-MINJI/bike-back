package com.bikeprojectminji.bikeback.routing.service;

import java.math.BigDecimal;

public record BicycleRoutePoint(
        BigDecimal lat,
        BigDecimal lon,
        String label
) {
}
