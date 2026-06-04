package com.bikeprojectminji.bikeback.address.dto;

import java.util.List;

public record AddressSearchResponse(
        String status,
        int page,
        int size,
        int totalCount,
        String provider,
        String message,
        List<AddressCandidateResponse> candidates
) {
}
