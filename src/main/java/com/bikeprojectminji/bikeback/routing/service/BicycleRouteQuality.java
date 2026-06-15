package com.bikeprojectminji.bikeback.routing.service;

public record BicycleRouteQuality(
        String status,
        String message
) {

    public boolean usable() {
        return !"INVALID".equals(status);
    }
}
