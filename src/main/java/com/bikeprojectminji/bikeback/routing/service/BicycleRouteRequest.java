package com.bikeprojectminji.bikeback.routing.service;

import java.math.BigDecimal;

public record BicycleRouteRequest(
        BigDecimal originLat,
        BigDecimal originLon,
        BigDecimal destinationLat,
        BigDecimal destinationLon,
        String preference,
        String elevationPreference,
        String textIntent
) {

    public BicycleRouteRequest(
            BigDecimal originLat,
            BigDecimal originLon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String preference
    ) {
        this(originLat, originLon, destinationLat, destinationLon, preference, null, null);
    }

    public BicycleRouteRequest(
            BigDecimal originLat,
            BigDecimal originLon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String preference,
            String elevationPreference
    ) {
        this(originLat, originLon, destinationLat, destinationLon, preference, elevationPreference, null);
    }

    public BicycleRoutePreference routePreference() {
        return BicycleRoutePreference.from(preference, elevationPreference, textIntent);
    }
}
