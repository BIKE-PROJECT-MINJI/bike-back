package com.bikeprojectminji.bikeback.routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.InvalidRouteRequestException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(classes = {
        BicycleRoutingService.class,
        BicycleRouteQualityValidator.class,
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
        assertThat(plan.qualityStatus()).isEqualTo("VALID_WITH_WARNINGS");
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
        assertThat(plan.fallbackReason()).contains("primary provider");
        assertThat(plan.candidates()).isNotEmpty();
    }

    @Test
    @DisplayName("자전거 경로 검색은 모든 provider가 실패하면 retryable 503 원인을 보존한다")
    void routeThrowsServiceUnavailableWhenAllProvidersFail() {
        primaryClient.nextResult(BicycleRoutingProviderResult.providerFailure("KAKAO_MOBILITY", 11));
        fallbackClient.nextResult(BicycleRoutingProviderResult.providerFailure("FAKE", 7));

        assertThatThrownBy(() -> bicycleRoutingService.route(request("SCENERY_FIRST")))
                .isInstanceOf(RoutingProviderUnavailableException.class)
                .hasMessageContaining("일시적으로")
                .satisfies(exception -> assertThat(
                        ((RoutingProviderUnavailableException) exception).getRetryAfterSeconds()).isEqualTo(11));
    }

    @Test
    @DisplayName("provider가 정상 응답했지만 경로가 없으면 422 원인을 보존한다")
    void routeThrowsNotFoundWhenProviderHasNoRoute() {
        primaryClient.nextResult(BicycleRoutingProviderResult.noRoute("KAKAO_MOBILITY"));
        fallbackClient.nextResult(BicycleRoutingProviderResult.noRoute("FAKE"));

        assertThatThrownBy(() -> bicycleRoutingService.route(request("SCENERY_FIRST")))
                .isInstanceOf(RouteNotFoundException.class);
    }

    @Test
    @DisplayName("provider가 반환한 경로가 품질 기준을 통과하지 못하면 422 원인을 보존한다")
    void routeThrowsNotFoundWhenEveryCandidateIsRejectedByQuality() {
        BicycleRouteCandidate invalid = candidate("RECOMMENDED", 0, 1200, 82);
        primaryClient.nextResult(BicycleRoutingProviderResult.success("KAKAO_MOBILITY", List.of(invalid)));
        fallbackClient.nextResult(BicycleRoutingProviderResult.success("FAKE", List.of(invalid)));

        assertThatThrownBy(() -> bicycleRoutingService.route(request("SCENERY_FIRST")))
                .isInstanceOf(RouteNotFoundException.class)
                .hasMessageContaining("품질");
    }

    @Test
    @DisplayName("모든 provider가 quota를 반환하면 retryable 429 원인을 보존한다")
    void routeThrowsTooManyRequestsWhenProvidersAreQuotaLimited() {
        primaryClient.nextResult(BicycleRoutingProviderResult.quotaExceeded("KAKAO_MOBILITY", 7));
        fallbackClient.nextResult(BicycleRoutingProviderResult.quotaExceeded("FAKE", 7));

        assertThatThrownBy(() -> bicycleRoutingService.route(request("SCENERY_FIRST")))
                .isInstanceOf(RetryableTooManyRequestsException.class)
                .satisfies(exception -> assertThat(((RetryableTooManyRequestsException) exception).getRetryAfterSeconds()).isEqualTo(7));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mixedFailurePrecedenceCases")
    @DisplayName("혼합 routing 실패는 경로 없음/품질 탈락, provider 장애, quota 순으로 우선한다")
    void routeAppliesMixedFailurePrecedence(
            String description,
            BicycleRoutingProviderResult primaryResult,
            BicycleRoutingProviderResult fallbackResult,
            Class<? extends RuntimeException> expectedException
    ) {
        primaryClient.nextResult(primaryResult);
        fallbackClient.nextResult(fallbackResult);

        assertThatThrownBy(() -> bicycleRoutingService.route(request("SCENERY_FIRST")))
                .isInstanceOf(expectedException);
    }

    @Test
    @DisplayName("fallback reason은 primary의 경로 없음 원인을 숨기지 않는다")
    void routePreservesNoRouteFallbackReason() {
        primaryClient.nextResult(BicycleRoutingProviderResult.noRoute("KAKAO_MOBILITY"));
        fallbackClient.nextResult(success("FAKE"));

        BicycleRoutePlan plan = bicycleRoutingService.route(request("SCENERY_FIRST"));

        assertThat(plan.fallbackReason()).contains("경로 없음");
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
                .isInstanceOf(InvalidRouteRequestException.class)
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

    private static Stream<Arguments> mixedFailurePrecedenceCases() {
        BicycleRouteCandidate qualityRejected = new BicycleRouteCandidate(
                "RECOMMENDED",
                0,
                1200,
                List.of(
                        new BicycleRoutePoint(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780), "출발지"),
                        new BicycleRoutePoint(BigDecimal.valueOf(37.6026), BigDecimal.valueOf(126.9803), "도착지")
                ),
                "품질 탈락 fixture",
                82,
                70
        );
        BicycleRoutingProviderResult unavailable = BicycleRoutingProviderResult.providerFailure("PRIMARY");
        BicycleRoutingProviderResult quota = BicycleRoutingProviderResult.quotaExceeded("PRIMARY", 7);
        BicycleRoutingProviderResult noRoute = BicycleRoutingProviderResult.noRoute("PRIMARY");
        BicycleRoutingProviderResult quality = BicycleRoutingProviderResult.success("PRIMARY", List.of(qualityRejected));
        return Stream.of(
                Arguments.of("provider 장애 > quota", unavailable, quota, RoutingProviderUnavailableException.class),
                Arguments.of("경로 없음 > provider 장애", unavailable, noRoute, RouteNotFoundException.class),
                Arguments.of("품질 탈락 > provider 장애", unavailable, quality, RouteNotFoundException.class),
                Arguments.of("경로 없음 > quota", quota, noRoute, RouteNotFoundException.class),
                Arguments.of("품질 탈락 > quota", quota, quality, RouteNotFoundException.class),
                Arguments.of("경로 없음과 품질 탈락은 422", noRoute, quality, RouteNotFoundException.class)
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
