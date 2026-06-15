package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteElevationSummaryResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteRiskResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteRoutingMetadataResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePlan;
import com.bikeprojectminji.bikeback.routing.service.ElevationSummary;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AiRoutePlanComposer {

    private final RecommendationScoreCalculator recommendationScoreCalculator;
    private final AiRoutePlanDetailsFactory detailsFactory;

    public AiRoutePlanComposer() {
        this(new RecommendationScoreCalculator(), new AiRoutePlanDetailsFactory());
    }

    public AiRoutePlanComposer(RecommendationScoreCalculator recommendationScoreCalculator) {
        this(recommendationScoreCalculator, new AiRoutePlanDetailsFactory());
    }

    AiRoutePlanComposer(RecommendationScoreCalculator recommendationScoreCalculator, AiRoutePlanDetailsFactory detailsFactory) {
        this.recommendationScoreCalculator = recommendationScoreCalculator;
        this.detailsFactory = detailsFactory;
    }

    public AiRoutePlanResponse composeFallback(AiRoutePlanRequest request, AiRouteConditionContext context) {
        Optional<CurrentWeatherResponse> weather = context.weather();
        List<AiRouteRiskResponse> risks = detailsFactory.buildRisks(weather, context);
        List<ProviderEvidenceBadgeResponse> evidenceBadges = detailsFactory.buildEvidenceBadges(weather, context);
        RecommendationScore score = recommendationScoreCalculator.calculateFallback(
                request.rideStyle(),
                weather.isPresent(),
                detailsFactory.countUnknownEvidence(evidenceBadges)
        );
        return new AiRoutePlanResponse(
                "route-" + UUID.randomUUID(),
                "READY",
                buildSummary(request, weather),
                weather.isPresent() ? "medium" : "low",
                weather.map(CurrentWeatherResponse::weather).orElse(null),
                weather.map(CurrentWeatherResponse::wind).orElse(null),
                buildRoutePoints(request),
                risks,
                detailsFactory.buildActions(weather, risks),
                score.total(),
                score.toResponse(),
                detailsFactory.buildExplanation(request, score, evidenceBadges),
                evidenceBadges,
                false,
                null
        );
    }

    public AiRoutePlanResponse composeWithRouteCandidate(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            BicycleRouteCandidate candidate
    ) {
        return composeWithRouteCandidate(request, context, candidate, null);
    }

    public AiRoutePlanResponse composeWithRouteCandidate(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            BicycleRouteCandidate candidate,
            BicycleRoutePlan routePlan
    ) {
        Optional<CurrentWeatherResponse> weather = context.weather();
        List<AiRouteRiskResponse> risks = detailsFactory.buildRisks(weather, context);
        List<ProviderEvidenceBadgeResponse> evidenceBadges = detailsFactory.buildEvidenceBadges(weather, context);
        evidenceBadges.addAll(candidate.evidenceBadges().stream()
                .map(detailsFactory::toProviderEvidenceBadge)
                .toList());
        detailsFactory.canonicalRouteBadge(request).ifPresent(evidenceBadges::add);

        RecommendationScore score = recommendationScoreCalculator.calculateWithRouteEvidence(
                scorePreference(request),
                candidate.sceneryScore(),
                candidate.bikePathScore(),
                detailsFactory.countRouteWarningEvidence(candidate),
                detailsFactory.countUnknownEvidence(evidenceBadges),
                candidate.distanceMeters(),
                candidate.elevationSummary()
        );

        return new AiRoutePlanResponse(
                "route-" + UUID.randomUUID(),
                "READY",
                buildCandidateSummary(request, candidate, weather),
                weather.isPresent() ? "high" : "medium",
                weather.map(CurrentWeatherResponse::weather).orElse(null),
                weather.map(CurrentWeatherResponse::wind).orElse(null),
                candidate.polyline().stream()
                        .map(point -> new AiRoutePointResponse(point.lat(), point.lon(), point.label(), point.altitudeM()))
                        .toList(),
                risks,
                detailsFactory.buildActions(weather, risks),
                score.total(),
                score.toResponse(),
                detailsFactory.buildExplanation(request, score, evidenceBadges),
                evidenceBadges,
                false,
                toElevationSummaryResponse(candidate.elevationSummary()),
                AiRouteRoutingMetadataResponse.from(routePlan)
        );
    }

    private String buildSummary(AiRoutePlanRequest request, Optional<CurrentWeatherResponse> weather) {
        String destination = normalizeText(request.destinationLabel(), "가까운 순환 코스");
        if (weather.isEmpty()) {
            return destination + "까지 기본 안전 우선 경로를 제안합니다. 날씨와 노면 데이터는 다시 확인하세요.";
        }
        WeatherData weatherData = weather.get().weather();
        WindData windData = weather.get().wind();
        return destination + "까지 바람 " + windData.directionText() + " " + windData.speedKmh()
                + "km/h, " + weatherData.sky() + " 상태를 반영한 완만한 경로입니다.";
    }

    private String buildCandidateSummary(
            AiRoutePlanRequest request,
            BicycleRouteCandidate candidate,
            Optional<CurrentWeatherResponse> weather
    ) {
        String destination = normalizeText(request.destinationLabel(), "추천 도착지");
        String evidenceSummary = normalizeText(candidate.evidenceSummary(), "자전거 경로 provider 기준");
        if (weather.isEmpty()) {
            return destination + "까지 " + evidenceSummary + "을 반영한 경로입니다. 날씨 데이터는 다시 확인하세요.";
        }
        return destination + "까지 " + evidenceSummary + "와 현재 날씨를 반영한 경로입니다.";
    }

    private List<AiRoutePointResponse> buildRoutePoints(AiRoutePlanRequest request) {
        BigDecimal lat = request.lat();
        BigDecimal lon = request.lon();
        BigDecimal destinationLat = request.destinationLat() != null ? request.destinationLat() : lat.add(BigDecimal.valueOf(0.012));
        BigDecimal destinationLon = request.destinationLon() != null ? request.destinationLon() : lon.add(BigDecimal.valueOf(0.014));

        BigDecimal midLat = lat.add(destinationLat).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        BigDecimal midLon = lon.add(destinationLon).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);

        return List.of(
                new AiRoutePointResponse(lat, lon, "현재 위치"),
                new AiRoutePointResponse(midLat.add(BigDecimal.valueOf(0.004)), midLon, "바람 회피 구간"),
                new AiRoutePointResponse(destinationLat, destinationLon, normalizeText(request.destinationLabel(), "추천 도착지"))
        );
    }

    private AiRouteElevationSummaryResponse toElevationSummaryResponse(ElevationSummary elevationSummary) {
        if (elevationSummary == null || !elevationSummary.hasElevation()) {
            return null;
        }
        return new AiRouteElevationSummaryResponse(
                elevationSummary.totalAscentM(),
                elevationSummary.totalDescentM(),
                elevationSummary.minAltitudeM(),
                elevationSummary.maxAltitudeM(),
                elevationSummary.maxSlopePercent(),
                elevationSummary.averageSlopePercent()
        );
    }

    private String scorePreference(AiRoutePlanRequest request) {
        if (request.elevationPreference() != null && !request.elevationPreference().isBlank()) {
            return request.elevationPreference();
        }
        return request.rideStyle();
    }

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
