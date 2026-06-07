package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteTextPlanRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePoint;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingClient;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(classes = {
        AiRoutePlannerService.class,
        AiRoutePlanComposer.class,
        AiRoutePlannerServiceIntegrationTest.TestAiRoutePlannerConfig.class
})
class AiRoutePlannerServiceIntegrationTest {

    private final AiRoutePlannerService aiRoutePlannerService;
    private final ScenarioBicycleRoutingClient routingClient;
    private final ScenarioUserRoutePreferenceProvider preferenceProvider;

    @Autowired
    AiRoutePlannerServiceIntegrationTest(
            AiRoutePlannerService aiRoutePlannerService,
            ScenarioBicycleRoutingClient routingClient,
            ScenarioUserRoutePreferenceProvider preferenceProvider
    ) {
        this.aiRoutePlannerService = aiRoutePlannerService;
        this.routingClient = routingClient;
        this.preferenceProvider = preferenceProvider;
    }

    @Test
    @DisplayName("목적지가 있는 AI 경로 요청은 GraphHopper routing 실패를 기본 3점 fallback으로 숨기지 않는다")
    void planRejectsDestinationRouteWhenGraphHopperRoutingFails() {
        routingClient.nextResult(BicycleRoutingProviderResult.providerFailure("GRAPHHOPPER"));

        assertThatThrownBy(() -> aiRoutePlannerService.plan(request()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("GraphHopper");
    }

    @Test
    @DisplayName("AI 경로 추천은 요청 rideStyle이 비어 있으면 저장된 사용자 선호를 기본값으로 사용한다")
    void planUsesSavedPreferenceWhenRideStyleIsBlank() {
        preferenceProvider.nextRideStyle("SCENERY_FIRST");
        routingClient.nextResult(BicycleRoutingProviderResult.success("GRAPHHOPPER", List.of(candidate())));

        aiRoutePlannerService.plan("1", blankRideStyleRequest());

        assertThat(routingClient.lastRequest().preference()).isEqualTo("SCENERY_FIRST");
    }

    @Test
    @DisplayName("목적지가 없는 현재 위치 기반 AI 경로 요청도 기본 목적지를 계산해 GraphHopper routing을 사용한다")
    void planUsesGraphHopperRouteForCurrentLocationDefaultRequest() {
        routingClient.nextResult(BicycleRoutingProviderResult.success("GRAPHHOPPER", List.of(candidate())));

        AiRoutePlanResponse response = aiRoutePlannerService.plan("1", currentLocationOnlyRequest());

        assertThat(routingClient.lastRequest().destinationLat()).isEqualByComparingTo("37.4932");
        assertThat(routingClient.lastRequest().destinationLon()).isEqualByComparingTo("126.9667");
        assertThat(response.routePoints()).hasSize(2);
        assertThat(response.summary()).contains("현재 위치 기반 추천 코스");
    }

    @Test
    @DisplayName("텍스트 기반 AI 경로 추천은 오르막 의도를 남산 정석 루트와 업힐 선호로 변환한다")
    void planFromTextResolvesClimbIntentToNamsanCanonicalRoute() {
        routingClient.nextResult(BicycleRoutingProviderResult.success("GRAPHHOPPER", List.of(candidate())));

        AiRoutePlanResponse response = aiRoutePlannerService.planFromText("1", new AiRouteTextPlanRequest(
                BigDecimal.valueOf(37.4812),
                BigDecimal.valueOf(126.9527),
                "오르막이 많은 곳 추천"
        ));

        assertThat(routingClient.lastRequest().preference()).isEqualTo("SCENERY_FIRST");
        assertThat(routingClient.lastRequest().elevationPreference()).isEqualTo("CLIMB_FIRST");
        assertThat(routingClient.lastRequest().destinationLat()).isEqualByComparingTo("37.5512");
        assertThat(routingClient.lastRequest().destinationLon()).isEqualByComparingTo("126.9882");
        assertThat(response.summary()).contains("남산");
        assertThat(response.evidenceBadges()).extracting("source").contains("canonical-route");
    }

    private AiRoutePlanRequest request() {
        return new AiRoutePlanRequest(
                BigDecimal.valueOf(37.4812),
                BigDecimal.valueOf(126.9527),
                BigDecimal.valueOf(37.5404),
                BigDecimal.valueOf(127.0692),
                "건대입구",
                "SCENERY_FIRST"
        );
    }

    private AiRoutePlanRequest blankRideStyleRequest() {
        return new AiRoutePlanRequest(
                BigDecimal.valueOf(37.4812),
                BigDecimal.valueOf(126.9527),
                BigDecimal.valueOf(37.5404),
                BigDecimal.valueOf(127.0692),
                "건대입구",
                " "
        );
    }

    private AiRoutePlanRequest currentLocationOnlyRequest() {
        return new AiRoutePlanRequest(
                BigDecimal.valueOf(37.4812),
                BigDecimal.valueOf(126.9527),
                null,
                null,
                "현재 위치 기반 추천 코스",
                "BALANCED",
                "BALANCED_ELEVATION",
                null
        );
    }

    private BicycleRouteCandidate candidate() {
        return new BicycleRouteCandidate(
                "SCENERY_FIRST",
                1200,
                360,
                List.of(
                        new BicycleRoutePoint(BigDecimal.valueOf(37.4812), BigDecimal.valueOf(126.9527), "출발"),
                        new BicycleRoutePoint(BigDecimal.valueOf(37.5404), BigDecimal.valueOf(127.0692), "도착")
                ),
                "GraphHopper 테스트 경로",
                70,
                90
        );
    }

    @TestConfiguration
    static class TestAiRoutePlannerConfig {

        @Bean
        CurrentWeatherLookup currentWeatherLookup() {
            return (lat, lon) -> Optional.empty();
        }

        @Bean
        AiRouteWorkerClient aiRouteWorkerClient() {
            return (request, context, fallbackPlan) -> Optional.empty();
        }

        @Bean
        ScenarioBicycleRoutingClient scenarioBicycleRoutingClient() {
            return new ScenarioBicycleRoutingClient();
        }

        @Bean
        BicycleRoutingService bicycleRoutingService(ScenarioBicycleRoutingClient routingClient) {
            return new BicycleRoutingService(List.of(routingClient));
        }

        @Bean
        ScenarioUserRoutePreferenceProvider userRoutePreferenceProvider() {
            return new ScenarioUserRoutePreferenceProvider();
        }

        @Bean
        AiRouteTextIntentResolver aiRouteTextIntentResolver() {
            return new AiRouteTextIntentResolver();
        }
    }

    static class ScenarioBicycleRoutingClient implements BicycleRoutingClient {

        private BicycleRoutingProviderResult result = BicycleRoutingProviderResult.providerFailure("GRAPHHOPPER");
        private BicycleRouteRequest lastRequest;

        void nextResult(BicycleRoutingProviderResult result) {
            this.result = result;
        }

        BicycleRouteRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public BicycleRoutingProviderResult route(BicycleRouteRequest request) {
            this.lastRequest = request;
            return result;
        }
    }

    static class ScenarioUserRoutePreferenceProvider implements UserRoutePreferenceProvider {

        private Optional<String> rideStyle = Optional.empty();

        void nextRideStyle(String rideStyle) {
            this.rideStyle = Optional.of(rideStyle);
        }

        @Override
        public Optional<String> findDefaultRideStyle(String subject) {
            return rideStyle;
        }
    }
}
