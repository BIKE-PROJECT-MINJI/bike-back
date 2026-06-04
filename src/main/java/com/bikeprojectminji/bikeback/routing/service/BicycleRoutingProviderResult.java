package com.bikeprojectminji.bikeback.routing.service;

import java.util.List;

public record BicycleRoutingProviderResult(
        String status,
        String provider,
        List<BicycleRouteCandidate> candidates
) {

    public static BicycleRoutingProviderResult success(String provider, List<BicycleRouteCandidate> candidates) {
        return new BicycleRoutingProviderResult("SUCCESS", provider, List.copyOf(candidates));
    }

    public static BicycleRoutingProviderResult providerFailure(String provider) {
        return new BicycleRoutingProviderResult("PROVIDER_FAILURE", provider, List.of());
    }
}
