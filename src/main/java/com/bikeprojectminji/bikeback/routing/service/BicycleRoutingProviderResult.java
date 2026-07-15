package com.bikeprojectminji.bikeback.routing.service;

import java.util.List;

public record BicycleRoutingProviderResult(
        String status,
        String provider,
        List<BicycleRouteCandidate> candidates,
        boolean fallbackUsed,
        String fallbackReason,
        BicycleRoutingFailureCause failureCause,
        Integer retryAfterSeconds,
        boolean sameEndpointRetryable
) {

    public BicycleRoutingProviderResult(
            String status,
            String provider,
            List<BicycleRouteCandidate> candidates
    ) {
        this(status, provider, candidates, false, null, null, null, false);
    }

    public BicycleRoutingProviderResult(
            String status,
            String provider,
            List<BicycleRouteCandidate> candidates,
            boolean fallbackUsed,
            String fallbackReason
    ) {
        this(status, provider, candidates, fallbackUsed, fallbackReason, null, null, false);
    }

    public static BicycleRoutingProviderResult success(String provider, List<BicycleRouteCandidate> candidates) {
        return new BicycleRoutingProviderResult("SUCCESS", provider, List.copyOf(candidates), false, null, null, null, false);
    }

    public static BicycleRoutingProviderResult successWithFallback(
            String provider,
            List<BicycleRouteCandidate> candidates,
            String fallbackReason
    ) {
        return new BicycleRoutingProviderResult("SUCCESS", provider, List.copyOf(candidates), true, fallbackReason, null, null, false);
    }

    public static BicycleRoutingProviderResult providerFailure(String provider) {
        return failure("PROVIDER_FAILURE", provider, BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE, null, false);
    }

    public static BicycleRoutingProviderResult providerFailure(String provider, int retryAfterSeconds) {
        return failure(
                "PROVIDER_FAILURE",
                provider,
                BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE,
                Math.max(1, retryAfterSeconds),
                true
        );
    }

    public static BicycleRoutingProviderResult noRoute(String provider) {
        return failure("NO_ROUTE", provider, BicycleRoutingFailureCause.NO_ROUTE, null, false);
    }

    public static BicycleRoutingProviderResult quotaExceeded(String provider, int retryAfterSeconds) {
        return failure("QUOTA_EXCEEDED", provider, BicycleRoutingFailureCause.QUOTA_EXCEEDED, Math.max(1, retryAfterSeconds), false);
    }

    private static BicycleRoutingProviderResult failure(
            String status,
            String provider,
            BicycleRoutingFailureCause failureCause,
            Integer retryAfterSeconds,
            boolean sameEndpointRetryable
    ) {
        return new BicycleRoutingProviderResult(
                status,
                provider,
                List.of(),
                false,
                null,
                failureCause,
                retryAfterSeconds,
                sameEndpointRetryable
        );
    }
}
