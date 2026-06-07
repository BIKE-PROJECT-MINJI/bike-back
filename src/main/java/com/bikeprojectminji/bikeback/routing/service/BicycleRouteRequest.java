package com.bikeprojectminji.bikeback.routing.service;

import java.math.BigDecimal;

public record BicycleRouteRequest(
        BigDecimal originLat,
        BigDecimal originLon,
        BigDecimal destinationLat,
        BigDecimal destinationLon,
        String preference,
        String elevationPreference
) {

    public BicycleRouteRequest(
            BigDecimal originLat,
            BigDecimal originLon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String preference
    ) {
        this(originLat, originLon, destinationLat, destinationLon, preference, null);
    }
}
