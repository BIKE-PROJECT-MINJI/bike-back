package com.bikeprojectminji.bikeback.airoute.dto;

public record AiRouteRiskResponse(
        String type,
        String label,
        String severity,
        String summary
) {
}
