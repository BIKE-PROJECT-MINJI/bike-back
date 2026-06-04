package com.bikeprojectminji.bikeback.airoute.dto;

public record RecommendationScoreResponse(
        int total,
        int scenery,
        int bikePath,
        int safety,
        int condition,
        int preferenceFit,
        int distancePenalty,
        int unknownPenalty
) {
}
