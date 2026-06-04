package com.bikeprojectminji.bikeback.airoute.dto;

public record RecommendationExplanationResponse(
        String headline,
        String reason,
        String caution,
        String nextAction
) {
}
