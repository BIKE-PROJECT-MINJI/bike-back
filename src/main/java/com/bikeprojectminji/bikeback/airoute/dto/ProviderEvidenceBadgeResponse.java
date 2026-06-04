package com.bikeprojectminji.bikeback.airoute.dto;

public record ProviderEvidenceBadgeResponse(
        String source,
        String label,
        String status,
        String severity,
        String summary,
        String observedAt
) {
}
