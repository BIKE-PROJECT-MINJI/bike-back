package com.bikeprojectminji.bikeback.airoute.session.dto;

import com.bikeprojectminji.bikeback.airoute.dto.AiRouteElevationSummaryResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import java.math.BigDecimal;
import java.util.List;

public record AiRouteCandidateResponse(
        Long candidateId,
        String title,
        String summary,
        BigDecimal distanceKm,
        Integer estimatedDurationMin,
        Integer recommendationScore,
        AiRouteElevationSummaryResponse elevationSummary,
        List<AiRoutePointResponse> routePoints,
        Integer routePointCount,
        Long promotedCourseId
) {
}
