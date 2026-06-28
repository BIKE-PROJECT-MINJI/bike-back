package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteRiskResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.airoute.dto.RecommendationExplanationResponse;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.RouteEvidenceBadge;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class AiRoutePlanDetailsFactory {

    List<AiRouteRiskResponse> buildRisks(Optional<CurrentWeatherResponse> weather, AiRouteConditionContext context) {
        List<AiRouteRiskResponse> risks = new ArrayList<>();
        usableWeather(weather).ifPresent(current -> {
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

    List<ProviderEvidenceBadgeResponse> buildEvidenceBadges(Optional<CurrentWeatherResponse> weather, AiRouteConditionContext context) {
        List<ProviderEvidenceBadgeResponse> badges = new ArrayList<>();
        Optional<CurrentWeatherResponse> currentWeather = usableWeather(weather);
        if (currentWeather.isPresent()) {
            CurrentWeatherResponse current = currentWeather.get();
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
            badges.add(new ProviderEvidenceBadgeResponse("weather", "날씨", "UNKNOWN", "UNKNOWN", "날씨 정보 미확인", null));
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

    List<String> buildActions(Optional<CurrentWeatherResponse> weather, List<AiRouteRiskResponse> risks) {
        List<String> actions = new ArrayList<>();
        actions.add("출발 전 브레이크와 라이트를 확인하세요.");
        if (usableWeather(weather).isEmpty()) {
            actions.add("날씨 데이터가 없으므로 출발 전 외부 날씨를 한 번 더 확인하세요.");
        }
        if (risks.stream().anyMatch(risk -> "high".equals(risk.severity()))) {
            actions.add("위험도가 높은 조건이 있어 속도를 낮추고 우회 후보를 준비하세요.");
        }
        actions.add("공사/노면 데이터는 AI worker 연동 후 자동 갱신됩니다.");
        return actions;
    }

    RecommendationExplanationResponse buildExplanation(
            AiRoutePlanRequest request,
            RecommendationScore score,
            List<ProviderEvidenceBadgeResponse> evidenceBadges
    ) {
        String destination = normalizeText(request.destinationLabel(), "추천 도착지");
        return new RecommendationExplanationResponse(
                destination + "까지 자전거 여행길을 선별했어요.",
                "경치 " + score.scenery() + ", 자전거도로 " + score.bikePath() + ", 고도 " + score.elevation() + ", 선호도 " + score.preferenceFit() + " 기준으로 골랐어요.",
                buildCaution(evidenceBadges),
                "이 경로로 출발"
        );
    }

    int countUnknownEvidence(List<ProviderEvidenceBadgeResponse> evidenceBadges) {
        return (int) evidenceBadges.stream()
                .filter(badge -> "UNKNOWN".equals(badge.status()))
                .count();
    }

    int countRouteWarningEvidence(BicycleRouteCandidate candidate) {
        return (int) candidate.evidenceBadges().stream()
                .filter(badge -> "WARNING".equals(badge.status()))
                .count();
    }

    ProviderEvidenceBadgeResponse toProviderEvidenceBadge(RouteEvidenceBadge badge) {
        return new ProviderEvidenceBadgeResponse(
                badge.source(),
                badge.label(),
                badge.status(),
                badge.severity(),
                badge.summary(),
                null
        );
    }

    private Optional<CurrentWeatherResponse> usableWeather(Optional<CurrentWeatherResponse> weather) {
        return weather.filter(current -> current.weather() != null && current.wind() != null);
    }

    Optional<ProviderEvidenceBadgeResponse> canonicalRouteBadge(AiRoutePlanRequest request) {
        if ("CANONICAL_NAMSAN_NATIONAL_THEATER".equals(request.textIntent())) {
            return Optional.of(new ProviderEvidenceBadgeResponse(
                    "canonical-route",
                    "남산 정석 루트",
                    "VERIFIED",
                    "INFO",
                    "남산 업힐은 국립극장 접근과 남산 내부/남측 순환로 주의 정책을 우선 반영합니다.",
                    null
            ));
        }
        if ("TEXT_RIVER_VIEW".equals(request.textIntent())) {
            return Optional.of(new ProviderEvidenceBadgeResponse(
                    "canonical-route",
                    "강변 조망 루트",
                    "VERIFIED",
                    "INFO",
                    "강변 조망과 자전거도로 연결성을 우선 반영합니다.",
                    null
            ));
        }
        if ("TEXT_FLAT_RIVERSIDE".equals(request.textIntent())) {
            return Optional.of(new ProviderEvidenceBadgeResponse(
                    "canonical-route",
                    "평지형 하천 루트",
                    "VERIFIED",
                    "INFO",
                    "상승고도와 최대경사가 낮은 하천 연결 후보를 우선 반영합니다.",
                    null
            ));
        }
        return Optional.empty();
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

    private String normalizeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
