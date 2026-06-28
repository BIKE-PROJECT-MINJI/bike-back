package com.bikeprojectminji.bikeback.airoute.dto;

import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import java.util.List;

public record AiRoutePlanResponse(
        String planId,
        String status,
        String summary,
        String confidence,
        WeatherData weather,
        WindData wind,
        List<AiRoutePointResponse> routePoints,
        List<AiRouteRiskResponse> risks,
        List<String> actions,
        int recommendationScore,
        RecommendationScoreResponse scoreBreakdown,
        RecommendationExplanationResponse explanation,
        List<ProviderEvidenceBadgeResponse> evidenceBadges,
        boolean aiGenerated,
        AiRouteElevationSummaryResponse elevationSummary,
        AiRouteRoutingMetadataResponse routingMetadata,
        AiRouteWorkerMetadataResponse aiWorkerMetadata,
        String preferenceSummary,
        String elevationStatus,
        String sceneryEvidenceStatus
) {

    public AiRoutePlanResponse(
            String planId,
            String status,
            String summary,
            String confidence,
            WeatherData weather,
            WindData wind,
            List<AiRoutePointResponse> routePoints,
            List<AiRouteRiskResponse> risks,
            List<String> actions,
            int recommendationScore,
            RecommendationScoreResponse scoreBreakdown,
            RecommendationExplanationResponse explanation,
            List<ProviderEvidenceBadgeResponse> evidenceBadges,
            boolean aiGenerated
    ) {
        this(
                planId,
                status,
                summary,
                confidence,
                weather,
                wind,
                routePoints,
                risks,
                actions,
                recommendationScore,
                scoreBreakdown,
                explanation,
                evidenceBadges,
                aiGenerated,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public AiRoutePlanResponse(
            String planId,
            String status,
            String summary,
            String confidence,
            WeatherData weather,
            WindData wind,
            List<AiRoutePointResponse> routePoints,
            List<AiRouteRiskResponse> risks,
            List<String> actions,
            int recommendationScore,
            RecommendationScoreResponse scoreBreakdown,
            RecommendationExplanationResponse explanation,
            List<ProviderEvidenceBadgeResponse> evidenceBadges,
            boolean aiGenerated,
            AiRouteElevationSummaryResponse elevationSummary
    ) {
        this(
                planId,
                status,
                summary,
                confidence,
                weather,
                wind,
                routePoints,
                risks,
                actions,
                recommendationScore,
                scoreBreakdown,
                explanation,
                evidenceBadges,
                aiGenerated,
                elevationSummary,
                null,
                null,
                null,
                null,
                null
        );
    }

    public AiRoutePlanResponse(
            String planId,
            String status,
            String summary,
            String confidence,
            WeatherData weather,
            WindData wind,
            List<AiRoutePointResponse> routePoints,
            List<AiRouteRiskResponse> risks,
            List<String> actions,
            int recommendationScore,
            RecommendationScoreResponse scoreBreakdown,
            RecommendationExplanationResponse explanation,
            List<ProviderEvidenceBadgeResponse> evidenceBadges,
            boolean aiGenerated,
            AiRouteElevationSummaryResponse elevationSummary,
            AiRouteRoutingMetadataResponse routingMetadata
    ) {
        this(
                planId,
                status,
                summary,
                confidence,
                weather,
                wind,
                routePoints,
                risks,
                actions,
                recommendationScore,
                scoreBreakdown,
                explanation,
                evidenceBadges,
                aiGenerated,
                elevationSummary,
                routingMetadata,
                null,
                null,
                null,
                null
        );
    }

    public AiRoutePlanResponse(
            String planId,
            String status,
            String summary,
            String confidence,
            WeatherData weather,
            WindData wind,
            List<AiRoutePointResponse> routePoints,
            List<AiRouteRiskResponse> risks,
            List<String> actions,
            int recommendationScore,
            RecommendationScoreResponse scoreBreakdown,
            RecommendationExplanationResponse explanation,
            List<ProviderEvidenceBadgeResponse> evidenceBadges,
            boolean aiGenerated,
            AiRouteElevationSummaryResponse elevationSummary,
            AiRouteRoutingMetadataResponse routingMetadata,
            String preferenceSummary,
            String elevationStatus,
            String sceneryEvidenceStatus
    ) {
        this(
                planId,
                status,
                summary,
                confidence,
                weather,
                wind,
                routePoints,
                risks,
                actions,
                recommendationScore,
                scoreBreakdown,
                explanation,
                evidenceBadges,
                aiGenerated,
                elevationSummary,
                routingMetadata,
                null,
                preferenceSummary,
                elevationStatus,
                sceneryEvidenceStatus
        );
    }

    public AiRoutePlanResponse withAiWorkerMetadata(AiRouteWorkerMetadataResponse metadata) {
        return new AiRoutePlanResponse(
                planId,
                status,
                summary,
                confidence,
                weather,
                wind,
                routePoints,
                risks,
                actions,
                recommendationScore,
                scoreBreakdown,
                explanation,
                evidenceBadges,
                aiGenerated,
                elevationSummary,
                routingMetadata,
                metadata,
                preferenceSummary,
                elevationStatus,
                sceneryEvidenceStatus
        );
    }
}
