package com.bikeprojectminji.bikeback.airoute.dto;

public record RecommendationScoreResponse(
        int total,
        int scenery,
        int bikePath,
        int safety,
        int condition,
        int elevation,
        int preferenceFit,
        int distancePenalty,
        int unknownPenalty
) {

    public RecommendationScoreResponse(
            int total,
            int scenery,
            int bikePath,
            int safety,
            int condition,
            int preferenceFit,
            int distancePenalty,
            int unknownPenalty
    ) {
        this(total, scenery, bikePath, safety, condition, 0, preferenceFit, distancePenalty, unknownPenalty);
    }
}
