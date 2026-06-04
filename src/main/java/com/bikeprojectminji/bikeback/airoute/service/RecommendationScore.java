package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.RecommendationScoreResponse;

public record RecommendationScore(
        int total,
        int scenery,
        int bikePath,
        int safety,
        int condition,
        int preferenceFit,
        int distancePenalty,
        int unknownPenalty
) {

    public RecommendationScoreResponse toResponse() {
        return new RecommendationScoreResponse(
                total,
                scenery,
                bikePath,
                safety,
                condition,
                preferenceFit,
                distancePenalty,
                unknownPenalty
        );
    }
}
