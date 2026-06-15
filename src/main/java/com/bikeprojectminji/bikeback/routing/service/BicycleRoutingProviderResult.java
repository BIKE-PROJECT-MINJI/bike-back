package com.bikeprojectminji.bikeback.routing.service;

import java.util.List;

public record BicycleRoutingProviderResult(
        String status,
        String provider,
        List<BicycleRouteCandidate> candidates,
        boolean fallbackUsed,
        String fallbackReason
) {

    public BicycleRoutingProviderResult(
            String status,
            String provider,
            List<BicycleRouteCandidate> candidates
    ) {
        this(status, provider, candidates, false, null);
    }

    public static BicycleRoutingProviderResult success(String provider, List<BicycleRouteCandidate> candidates) {
        return new BicycleRoutingProviderResult("SUCCESS", provider, List.copyOf(candidates), false, null);
    }

    public static BicycleRoutingProviderResult successWithFallback(
            String provider,
            List<BicycleRouteCandidate> candidates,
            String fallbackReason
    ) {
        return new BicycleRoutingProviderResult("SUCCESS", provider, List.copyOf(candidates), true, fallbackReason);
    }

    public static BicycleRoutingProviderResult providerFailure(String provider) {
        return new BicycleRoutingProviderResult("PROVIDER_FAILURE", provider, List.of(), false, null);
    }
}
