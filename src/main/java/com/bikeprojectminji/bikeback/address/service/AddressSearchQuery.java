package com.bikeprojectminji.bikeback.address.service;

public record AddressSearchQuery(
        String rawQuery,
        int page,
        int size
) {
}
