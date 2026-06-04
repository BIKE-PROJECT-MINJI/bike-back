package com.bikeprojectminji.bikeback.address.service;

import java.util.List;

public record AddressSearchProviderResult(
        AddressSearchProviderStatus status,
        String provider,
        List<AddressCandidate> candidates
) {

    public static AddressSearchProviderResult success(String provider, List<AddressCandidate> candidates) {
        return new AddressSearchProviderResult(AddressSearchProviderStatus.SUCCESS, provider, List.copyOf(candidates));
    }

    public static AddressSearchProviderResult empty(String provider) {
        return new AddressSearchProviderResult(AddressSearchProviderStatus.EMPTY, provider, List.of());
    }

    public static AddressSearchProviderResult providerFailure(String provider) {
        return new AddressSearchProviderResult(AddressSearchProviderStatus.PROVIDER_FAILURE, provider, List.of());
    }

    public static AddressSearchProviderResult rateLimited(String provider) {
        return new AddressSearchProviderResult(AddressSearchProviderStatus.RATE_LIMITED, provider, List.of());
    }
}
