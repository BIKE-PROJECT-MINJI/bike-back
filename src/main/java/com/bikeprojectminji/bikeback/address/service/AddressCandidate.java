package com.bikeprojectminji.bikeback.address.service;

import com.bikeprojectminji.bikeback.address.dto.AddressCandidateResponse;
import java.math.BigDecimal;

public record AddressCandidate(
        String candidateId,
        String label,
        String address,
        BigDecimal lat,
        BigDecimal lon,
        String source,
        String category,
        String confidence
) {

    public AddressCandidateResponse toResponse() {
        return new AddressCandidateResponse(candidateId, label, address, lat, lon, source, category, confidence);
    }
}
