package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePoint;
import com.bikeprojectminji.bikeback.routing.service.RouteEvidenceBadge;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRoutePlanComposerTest {

    private final AiRoutePlanComposer composer = new AiRoutePlanComposer();

    @Test
    @DisplayName("AI worker가 없으면 날씨와 위험요소를 반영한 fallback 경로를 만든다")
    void composeFallbackRoutePlan() {
        AiRoutePlanRequest request = new AiRoutePlanRequest(
                BigDecimal.valueOf(37.48),
                BigDecimal.valueOf(126.95),
                null,
                null,
                "관악 순환",
                "balanced"
        );
        CurrentWeatherResponse weather = new CurrentWeatherResponse(
                new WeatherData(17, "clear", "none"),
                new WindData(21, "북동", 61),
                false,
                false
        );

        AiRoutePlanResponse response = composer.composeFallback(
                request,
                new AiRouteConditionContext(
                        Optional.of(weather),
                        "공사 정보 미확인",
                        "노면 정보 미확인"
                )
        );

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.aiGenerated()).isFalse();
        assertThat(response.summary()).contains("관악 순환", "북동");
        assertThat(response.routePoints()).hasSize(3);
        assertThat(response.risks())
                .extracting("type")
                .contains("weather", "construction", "surface");
        assertThat(response.actions()).isNotEmpty();
        assertThat(response.recommendationScore()).isBetween(0, 100);
        assertThat(response.scoreBreakdown().total()).isEqualTo(response.recommendationScore());
        assertThat(response.explanation().headline()).contains("관악 순환");
        assertThat(response.explanation().nextAction()).isEqualTo("이 경로로 출발");
        assertThat(response.evidenceBadges())
                .extracting("status")
                .contains("WARNING", "UNKNOWN");
        assertThat(response.explanation().caution())
                .doesNotContain("공사 없음")
                .doesNotContain("노면 안전")
                .contains("정보 없음");
    }

    @Test
    @DisplayName("route candidate evidence는 AI route badge와 score breakdown에 반영된다")
    void composeRouteCandidateIncludesRouteEvidenceBadgesAndScore() {
        AiRoutePlanRequest request = new AiRoutePlanRequest(
                BigDecimal.valueOf(37.48),
                BigDecimal.valueOf(126.95),
                BigDecimal.valueOf(37.50),
                BigDecimal.valueOf(126.98),
                "관악 순환",
                "BIKE_PATH_FIRST"
        );
        BicycleRouteCandidate candidate = new BicycleRouteCandidate(
                "BIKE_PATH",
                5200,
                1420,
                List.of(
                        new BicycleRoutePoint(BigDecimal.valueOf(37.48), BigDecimal.valueOf(126.95), "출발"),
                        new BicycleRoutePoint(BigDecimal.valueOf(37.50), BigDecimal.valueOf(126.98), "도착")
                ),
                "GraphHopper OSM path detail: cycleway, asphalt",
                95,
                72,
                List.of(
                        new RouteEvidenceBadge("graphhopper.road_class", "자전거도로", "VERIFIED", "INFO", "cycleway 확인"),
                        new RouteEvidenceBadge("graphhopper.surface", "노면", "UNKNOWN", "UNKNOWN", "surface 태그 미확인")
                )
        );

        AiRoutePlanResponse response = composer.composeWithRouteCandidate(
                request,
                new AiRouteConditionContext(Optional.empty(), "공사 정보 미확인", "노면 정보 미확인"),
                candidate
        );

        assertThat(response.routePoints()).hasSize(2);
        assertThat(response.scoreBreakdown().bikePath()).isEqualTo(95);
        assertThat(response.scoreBreakdown().unknownPenalty()).isGreaterThan(0);
        assertThat(response.evidenceBadges()).extracting("source")
                .contains("graphhopper.road_class", "graphhopper.surface");
    }
}
