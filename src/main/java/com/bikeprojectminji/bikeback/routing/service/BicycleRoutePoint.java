package com.bikeprojectminji.bikeback.routing.service;

import java.math.BigDecimal;

public record BicycleRoutePoint(
        BigDecimal lat,
        BigDecimal lon,
        String label,
        BigDecimal altitudeM
) {

    public BicycleRoutePoint(BigDecimal lat, BigDecimal lon, String label) {
        this(lat, lon, label, null);
    }
}
