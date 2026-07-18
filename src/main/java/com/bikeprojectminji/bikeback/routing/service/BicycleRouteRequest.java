package com.bikeprojectminji.bikeback.routing.service;

import java.math.BigDecimal;

public record BicycleRouteRequest(
        BigDecimal originLat,
        BigDecimal originLon,
        BigDecimal destinationLat,
        BigDecimal destinationLon,
        String preference,
        String elevationPreference,
        String textIntent,
        ProviderCallBudget providerCallBudget
) {

    public BicycleRouteRequest(
            BigDecimal originLat,
            BigDecimal originLon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String preference
    ) {
        this(originLat, originLon, destinationLat, destinationLon, preference, null, null, null);
    }

    public BicycleRouteRequest(
            BigDecimal originLat,
            BigDecimal originLon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String preference,
            String elevationPreference
    ) {
        this(originLat, originLon, destinationLat, destinationLon, preference, elevationPreference, null, null);
    }

    public BicycleRouteRequest(
            BigDecimal originLat,
            BigDecimal originLon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String preference,
            String elevationPreference,
            String textIntent
    ) {
        this(originLat, originLon, destinationLat, destinationLon, preference, elevationPreference, textIntent, null);
    }

    public BicycleRoutePreference routePreference() {
        return BicycleRoutePreference.from(preference, elevationPreference, textIntent);
    }

    public BicycleRouteRequest withProviderCallBudget(ProviderCallBudget budget) {
        return new BicycleRouteRequest(
                originLat,
                originLon,
                destinationLat,
                destinationLon,
                preference,
                elevationPreference,
                textIntent,
                budget
        );
    }
}
