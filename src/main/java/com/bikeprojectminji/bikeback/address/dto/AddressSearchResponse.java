package com.bikeprojectminji.bikeback.address.dto;

import java.util.List;

public record AddressSearchResponse(
        String status,
        int page,
        int size,
        int totalCount,
        String provider,
        String primaryProvider,
        boolean fallbackUsed,
        String fallbackReason,
        String message,
        List<AddressCandidateResponse> candidates
) {

    public AddressSearchResponse(
            String status,
            int page,
            int size,
            int totalCount,
            String provider,
            String message,
            List<AddressCandidateResponse> candidates
    ) {
        this(status, page, size, totalCount, provider, provider, false, null, message, candidates);
    }
}
