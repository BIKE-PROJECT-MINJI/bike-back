package com.bikeprojectminji.bikeback.airoute.session.dto;

import com.bikeprojectminji.bikeback.airoute.dto.AiRouteElevationSummaryResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteRoutingMetadataResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.airoute.dto.RecommendationScoreResponse;
import java.math.BigDecimal;
import java.util.List;

public record AiRouteCandidateResponse(
        Long candidateId,
        String title,
        String summary,
        BigDecimal distanceKm,
        Integer estimatedDurationMin,
        Integer recommendationScore,
        RecommendationScoreResponse scoreBreakdown,
        List<ProviderEvidenceBadgeResponse> evidenceBadges,
        AiRouteElevationSummaryResponse elevationSummary,
        AiRouteRoutingMetadataResponse routingMetadata,
        String preferenceSummary,
        String elevationStatus,
        String sceneryEvidenceStatus,
        List<AiRoutePointResponse> routePoints,
        Integer routePointCount,
        Long promotedCourseId
) {
}
