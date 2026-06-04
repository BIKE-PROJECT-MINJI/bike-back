package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteRiskResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.airoute.dto.RecommendationExplanationResponse;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.RouteEvidenceBadge;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AiRoutePlanComposer {

    private final RecommendationScoreCalculator recommendationScoreCalculator;

    public AiRoutePlanComposer() {
        this(new RecommendationScoreCalculator());
    }

    public AiRoutePlanComposer(RecommendationScoreCalculator recommendationScoreCalculator) {
        this.recommendationScoreCalculator = recommendationScoreCalculator;
    }

    public AiRoutePlanResponse composeFallback(AiRoutePlanRequest request, AiRouteConditionContext context) {
        Optional<CurrentWeatherResponse> weather = context.weather();
        List<AiRouteRiskResponse> risks = buildRisks(weather, context);
        List<ProviderEvidenceBadgeResponse> evidenceBadges = buildEvidenceBadges(weather, context);
        RecommendationScore score = recommendationScoreCalculator.calculateFallback(
                request.rideStyle(),
                weather.isPresent(),
                countUnknownEvidence(evidenceBadges)
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
                buildActions(weather, risks),
                score.total(),
                score.toResponse(),
                buildExplanation(request, score, evidenceBadges),
                evidenceBadges,
                false
        );
    }

    public AiRoutePlanResponse composeWithRouteCandidate(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            BicycleRouteCandidate candidate
    ) {
        Optional<CurrentWeatherResponse> weather = context.weather();
        List<AiRouteRiskResponse> risks = buildRisks(weather, context);
        List<ProviderEvidenceBadgeResponse> evidenceBadges = buildEvidenceBadges(weather, context);
        evidenceBadges.addAll(candidate.evidenceBadges().stream()
                .map(this::toProviderEvidenceBadge)
                .toList());

        RecommendationScore score = recommendationScoreCalculator.calculateWithRouteEvidence(
                request.rideStyle(),
                candidate.sceneryScore(),
                candidate.bikePathScore(),
                countRouteWarningEvidence(candidate),
                countUnknownEvidence(evidenceBadges),
                candidate.distanceMeters()
        );

        return new AiRoutePlanResponse(
                "route-" + UUID.randomUUID(),
                "READY",
                buildCandidateSummary(request, candidate, weather),
                weather.isPresent() ? "high" : "medium",
                weather.map(CurrentWeatherResponse::weather).orElse(null),
                weather.map(CurrentWeatherResponse::wind).orElse(null),
                candidate.polyline().stream()
                        .map(point -> new AiRoutePointResponse(point.lat(), point.lon(), point.label()))
                        .toList(),
                risks,
                buildActions(weather, risks),
                score.total(),
                score.toResponse(),
                buildExplanation(request, score, evidenceBadges),
                evidenceBadges,
                false
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

    private List<AiRouteRiskResponse> buildRisks(Optional<CurrentWeatherResponse> weather, AiRouteConditionContext context) {
        List<AiRouteRiskResponse> risks = new ArrayList<>();
        weather.ifPresent(current -> {
            if (current.wind().speedKmh() >= 18) {
                risks.add(new AiRouteRiskResponse("weather", "강한 바람", "medium", "측풍이 강해 한강변 직선 구간은 피하는 편이 좋습니다."));
            }
            if (!"none".equals(current.weather().precipType())) {
                risks.add(new AiRouteRiskResponse("weather", "강수 가능", "high", "노면 미끄러짐과 제동거리 증가를 고려해야 합니다."));
            }
        });
        risks.add(new AiRouteRiskResponse("construction", "공사 정보", "unknown", context.constructionSummary()));
        risks.add(new AiRouteRiskResponse("surface", "노면 정보", "unknown", context.roadSurfaceSummary()));
        return risks;
    }

    private List<ProviderEvidenceBadgeResponse> buildEvidenceBadges(Optional<CurrentWeatherResponse> weather, AiRouteConditionContext context) {
        List<ProviderEvidenceBadgeResponse> badges = new ArrayList<>();
        if (weather.isPresent()) {
            CurrentWeatherResponse current = weather.get();
            boolean weatherRisk = current.wind().speedKmh() >= 18 || !"none".equals(current.weather().precipType());
            badges.add(new ProviderEvidenceBadgeResponse(
                    "weather",
                    "날씨",
                    weatherRisk ? "WARNING" : "VERIFIED",
                    weatherRisk ? "MEDIUM" : "INFO",
                    current.weather().sky() + ", " + current.wind().directionText() + "풍 " + current.wind().speedKmh() + "km/h",
                    null
            ));
        } else {
            badges.add(new ProviderEvidenceBadgeResponse(
                    "weather",
                    "날씨",
                    "UNKNOWN",
                    "UNKNOWN",
                    "날씨 정보 미확인",
                    null
            ));
        }
        badges.add(new ProviderEvidenceBadgeResponse(
                "construction",
                "공사",
                "UNKNOWN",
                "UNKNOWN",
                normalizeText(context.constructionSummary(), "공사 정보 미확인"),
                null
        ));
        badges.add(new ProviderEvidenceBadgeResponse(
                "surface",
                "노면",
                "UNKNOWN",
                "UNKNOWN",
                normalizeText(context.roadSurfaceSummary(), "노면 정보 미확인"),
                null
        ));
        return badges;
    }

    private List<String> buildActions(Optional<CurrentWeatherResponse> weather, List<AiRouteRiskResponse> risks) {
        List<String> actions = new ArrayList<>();
        actions.add("출발 전 브레이크와 라이트를 확인하세요.");
        if (weather.isEmpty()) {
            actions.add("날씨 데이터가 없으므로 출발 전 외부 날씨를 한 번 더 확인하세요.");
        }
        if (risks.stream().anyMatch(risk -> "high".equals(risk.severity()))) {
            actions.add("위험도가 높은 조건이 있어 속도를 낮추고 우회 후보를 준비하세요.");
        }
        actions.add("공사/노면 데이터는 AI worker 연동 후 자동 갱신됩니다.");
        return actions;
    }

    private RecommendationExplanationResponse buildExplanation(
            AiRoutePlanRequest request,
            RecommendationScore score,
            List<ProviderEvidenceBadgeResponse> evidenceBadges
    ) {
        String destination = normalizeText(request.destinationLabel(), "추천 도착지");
        return new RecommendationExplanationResponse(
                destination + "까지 자전거 여행길을 선별했어요.",
                "경치 " + score.scenery() + ", 자전거도로 " + score.bikePath() + ", 선호도 " + score.preferenceFit() + " 기준으로 골랐어요.",
                buildCaution(evidenceBadges),
                "이 경로로 출발"
        );
    }

    private String buildCaution(List<ProviderEvidenceBadgeResponse> evidenceBadges) {
        boolean hasUnknown = evidenceBadges.stream().anyMatch(badge -> "UNKNOWN".equals(badge.status()));
        boolean hasFailed = evidenceBadges.stream().anyMatch(badge -> "FAILED".equals(badge.status()));
        boolean hasWarning = evidenceBadges.stream().anyMatch(badge -> "WARNING".equals(badge.status()));

        if (hasFailed) {
            return "일부 provider 확인 실패가 있어 출발 전 조건을 다시 확인하세요.";
        }
        if (hasWarning && hasUnknown) {
            return "주의 조건이 있고 공사/노면 정보는 일부 구간 정보 없음이라 출발 전 확인이 필요해요.";
        }
        if (hasWarning) {
            return "주의 조건이 있어 속도를 낮추고 우회 가능성을 열어두세요.";
        }
        if (hasUnknown) {
            return "공사/노면 정보는 일부 구간 정보 없음이라 출발 전 확인이 필요해요.";
        }
        return "현재 확인된 조건 기준으로 큰 주의 항목은 없습니다.";
    }

    private int countUnknownEvidence(List<ProviderEvidenceBadgeResponse> evidenceBadges) {
        return (int) evidenceBadges.stream()
                .filter(badge -> "UNKNOWN".equals(badge.status()))
                .count();
    }

    private int countRouteWarningEvidence(BicycleRouteCandidate candidate) {
        return (int) candidate.evidenceBadges().stream()
                .filter(badge -> "WARNING".equals(badge.status()))
                .count();
    }

    private ProviderEvidenceBadgeResponse toProviderEvidenceBadge(RouteEvidenceBadge badge) {
        return new ProviderEvidenceBadgeResponse(
                badge.source(),
                badge.label(),
                badge.status(),
                badge.severity(),
                badge.summary(),
                null
        );
    }

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
