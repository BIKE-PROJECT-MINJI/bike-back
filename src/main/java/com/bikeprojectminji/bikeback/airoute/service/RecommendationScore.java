package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.RecommendationScoreResponse;

public record RecommendationScore(
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

    public RecommendationScore(
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

    public RecommendationScoreResponse toResponse() {
        return new RecommendationScoreResponse(
                total,
                scenery,
                bikePath,
                safety,
                condition,
                elevation,
                preferenceFit,
                distancePenalty,
                unknownPenalty
        );
    }
}
