package com.bikeprojectminji.bikeback.routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(classes = {
        BicycleRoutingService.class,
        BicycleRoutingServiceIntegrationTest.TestRoutingConfig.class
})
class BicycleRoutingServiceIntegrationTest {

    private final BicycleRoutingService bicycleRoutingService;
    private final ScenarioBicycleRoutingClient primaryClient;
    private final ScenarioBicycleRoutingClient fallbackClient;

    @Autowired
    BicycleRoutingServiceIntegrationTest(
            BicycleRoutingService bicycleRoutingService,
            ScenarioBicycleRoutingClient primaryClient,
            ScenarioBicycleRoutingClient fallbackClient
    ) {
        this.bicycleRoutingService = bicycleRoutingService;
        this.primaryClient = primaryClient;
        this.fallbackClient = fallbackClient;
    }

    @Test
    @DisplayName("자전거 경로 검색은 primary provider 후보를 반환한다")
    void routeReturnsPrimaryCandidates() {
        primaryClient.nextResult(success("KAKAO_MOBILITY"));
        fallbackClient.nextResult(success("FAKE"));

        BicycleRoutePlan plan = bicycleRoutingService.route(request("SCENERY_FIRST"));

        assertThat(plan.status()).isEqualTo("SUCCESS");
        assertThat(plan.provider()).isEqualTo("KAKAO_MOBILITY");
        assertThat(plan.fallbackUsed()).isFalse();
        assertThat(plan.candidates())
                .extracting(BicycleRouteCandidate::routeType)
                .containsExactly("RECOMMENDED", "SCENIC", "BIKE_PATH");
    }

    @Test
    @DisplayName("자전거 경로 검색은 primary 실패 시 fallback provider를 사용한다")
    void routeFallsBackWhenPrimaryFails() {
        primaryClient.nextResult(BicycleRoutingProviderResult.providerFailure("KAKAO_MOBILITY"));
        fallbackClient.nextResult(success("FAKE"));

        BicycleRoutePlan plan = bicycleRoutingService.route(request("BIKE_PATH_FIRST"));

        assertThat(plan.status()).isEqualTo("FALLBACK_USED");
        assertThat(plan.provider()).isEqualTo("FAKE");
        assertThat(plan.fallbackUsed()).isTrue();
        assertThat(plan.candidates()).isNotEmpty();
    }

    @Test
    @DisplayName("자전거 경로 검색은 모든 provider가 실패하면 ROUTING_FAILED를 반환한다")
    void routeReturnsFailureWhenAllProvidersFail() {
        primaryClient.nextResult(BicycleRoutingProviderResult.providerFailure("KAKAO_MOBILITY"));
        fallbackClient.nextResult(BicycleRoutingProviderResult.providerFailure("FAKE"));

        BicycleRoutePlan plan = bicycleRoutingService.route(request("SCENERY_FIRST"));

        assertThat(plan.status()).isEqualTo("ROUTING_FAILED");
        assertThat(plan.candidates()).isEmpty();
        assertThat(plan.message()).contains("경로");
    }

    @Test
    @DisplayName("자전거 경로 검색은 좌표가 없으면 provider 호출 전에 400 예외를 던진다")
    void routeRejectsMissingCoordinate() {
        BicycleRouteRequest request = new BicycleRouteRequest(
                null,
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.6026),
                BigDecimal.valueOf(126.9803),
                "SCENERY_FIRST"
        );

        assertThatThrownBy(() -> bicycleRoutingService.route(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("출발");
    }

    private BicycleRouteRequest request(String preference) {
        return new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.6026),
                BigDecimal.valueOf(126.9803),
                preference
        );
    }

    private BicycleRoutingProviderResult success(String provider) {
        return BicycleRoutingProviderResult.success(provider, List.of(
                candidate("RECOMMENDED", 4500, 1200, 82),
                candidate("SCENIC", 5100, 1500, 88),
                candidate("BIKE_PATH", 4800, 1320, 92)
        ));
    }

    private BicycleRouteCandidate candidate(String routeType, int distanceMeters, int durationSeconds, int bikePathScore) {
        return new BicycleRouteCandidate(
                routeType,
                distanceMeters,
                durationSeconds,
                List.of(
                        new BicycleRoutePoint(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780), "출발지"),
                        new BicycleRoutePoint(BigDecimal.valueOf(37.5840), BigDecimal.valueOf(126.9790), "중간 자전거길"),
                        new BicycleRoutePoint(BigDecimal.valueOf(37.6026), BigDecimal.valueOf(126.9803), "도착지")
                ),
                "자전거도로 우선 후보",
                bikePathScore,
                70
        );
    }

    @TestConfiguration
    static class TestRoutingConfig {

        @Bean
        ScenarioBicycleRoutingClient primaryClient() {
            return new ScenarioBicycleRoutingClient();
        }

        @Bean
        ScenarioBicycleRoutingClient fallbackClient() {
            return new ScenarioBicycleRoutingClient();
        }

        @Bean
        List<BicycleRoutingClient> bicycleRoutingClients(
                ScenarioBicycleRoutingClient primaryClient,
                ScenarioBicycleRoutingClient fallbackClient
        ) {
            return List.of(primaryClient, fallbackClient);
        }
    }

    static class ScenarioBicycleRoutingClient implements BicycleRoutingClient {

        private BicycleRoutingProviderResult result = BicycleRoutingProviderResult.providerFailure("SCENARIO");

        void nextResult(BicycleRoutingProviderResult result) {
            this.result = result;
        }

        @Override
        public BicycleRoutingProviderResult route(BicycleRouteRequest request) {
            return result;
        }
    }
}
