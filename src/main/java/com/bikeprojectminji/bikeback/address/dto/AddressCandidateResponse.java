package com.bikeprojectminji.bikeback.address.dto;

import java.math.BigDecimal;

public record AddressCandidateResponse(
        String candidateId,
        String label,
        String address,
        BigDecimal lat,
        BigDecimal lon,
        String source,
        String category,
        String confidence
) {
}
