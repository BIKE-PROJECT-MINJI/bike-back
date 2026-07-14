package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteTextPlanRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import com.bikeprojectminji.bikeback.global.ratelimit.InMemoryFixedWindowRateLimiter;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePoint;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePreference;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingClient;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRouteGoldenSetTest {

    private static final BigDecimal START_LAT = new BigDecimal("37.5000000");
    private static final BigDecimal START_LON = new BigDecimal("127.0000000");
    private final GoldenRoutingClient routingClient = new GoldenRoutingClient();
    private AiRoutePlannerService plannerService;

    @BeforeEach
    void setUp() {
        plannerService = new AiRoutePlannerService(
                (lat, lon) -> Optional.empty(),
                new DisabledAiRouteWorkerClient(),
                new AiRoutePlanComposer(),
                new BicycleRoutingService(List.of(routingClient)),
                subject -> Optional.empty(),
                new AiRouteTextIntentResolver()
        );
    }

    @Test
    @DisplayName("자연어 선호, provider 실패, evidence 부족, quota를 deterministic golden set으로 검증한다")
    void verifiesDeterministicGoldenSet() throws Exception {
        List<GoldenResult> results = new ArrayList<>();

        results.add(textSuccess("AI-01", "평지 한강 코스", "implemented text normalization", candidate()));
        results.add(planSuccess("AI-02", "structured BIKE_PATH_FIRST", request(
                new BigDecimal("37.5100000"), new BigDecimal("127.0100000"),
                "BIKE_PATH_FIRST", null, null), "implemented structured preference"));
        results.add(textSuccess("AI-03", "비포장 피해서 달리고 싶어", "unsupported: normalized to TEXT_BALANCED", candidate()));
        results.add(textSuccess("AI-04", "아주 짧은 코스", "unsupported: target distance not normalized", candidate()));
        results.add(textSuccess("AI-05", "30km 긴 코스", "unsupported: target distance not normalized", candidate()));
        results.add(planSuccess("AI-06", "explicit destination", request(
                new BigDecimal("37.5200000"), new BigDecimal("127.0200000"),
                "BALANCED", "BALANCED_ELEVATION", null), "destination supplied"));
        results.add(planSuccess("AI-07", "no destination", request(
                null, null, "BALANCED", "BALANCED_ELEVATION", null), "default destination calculated"));
        results.add(planSuccess("AI-08", "no elevation evidence", request(
                new BigDecimal("37.5100000"), new BigDecimal("127.0100000"),
                "SCENERY_FIRST", "FLAT_FIRST", null), "elevation remains UNAVAILABLE"));
        results.add(textSuccess("AI-09", "풍경 좋은 한강 코스", "scenery evidence is PARTIAL, not user quality proof", candidate()));
        results.add(planSuccess("AI-10", "AI worker disabled", request(
                new BigDecimal("37.5100000"), new BigDecimal("127.0100000"),
                "BALANCED", null, null), "backend plan returned with worker fallback metadata"));
        results.add(planFailure("AI-11", "GraphHopper down", BicycleRoutingProviderResult.providerFailure("GRAPHHOPPER"),
                "routing failure is 400; no fabricated coordinates"));
        results.add(planFailure("AI-12", "no candidate", BicycleRoutingProviderResult.success("GRAPHHOPPER", List.of()),
                "empty candidate is 400; no fabricated coordinates"));
        results.add(quotaFailure());

        assertThat(results).hasSize(13);
        assertThat(result(results, "AI-01").elevationPreference()).isEqualTo("FLAT_FIRST");
        assertThat(result(results, "AI-01").textIntent()).isEqualTo("TEXT_FLAT_RIVERSIDE");
        assertThat(result(results, "AI-02").routePriority()).isEqualTo("BIKE_PATH_FIRST");
        assertThat(result(results, "AI-03").textIntent()).isEqualTo("TEXT_BALANCED");
        assertThat(result(results, "AI-04").textIntent()).isEqualTo("TEXT_BALANCED");
        assertThat(result(results, "AI-05").textIntent()).isEqualTo("TEXT_BALANCED");
        assertThat(result(results, "AI-07").providerCallCount()).isEqualTo(1);
        assertThat(result(results, "AI-08").elevationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result(results, "AI-09").sceneryEvidenceStatus()).isEqualTo("PARTIAL");
        assertThat(result(results, "AI-10").aiWorkerFallbackUsed()).isTrue();
        assertThat(result(results, "AI-11").httpStatus()).isEqualTo(400);
        assertThat(result(results, "AI-12").routePointCount()).isZero();
        assertThat(result(results, "AI-13").httpStatus()).isEqualTo(429);

        Path output = Path.of("build", "public-evidence", "ai-route-golden-set.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), new GoldenEvidence(
                "ai-route-deterministic-golden-v1",
                System.getenv().getOrDefault("GIT_COMMIT", "working-tree"),
                "deterministic fake provider; not live inventory or real-user route quality",
                results.size(),
                results
        ));
    }

    private GoldenResult textSuccess(
            String testId,
            String rawInput,
            String note,
            BicycleRouteCandidate candidate
    ) {
        routingClient.next(BicycleRoutingProviderResult.success("FAKE_GRAPHHOPPER", List.of(candidate)));
        long startedNanos = System.nanoTime();
        AiRoutePlanResponse response = plannerService.planFromText("synthetic-user", new AiRouteTextPlanRequest(
                START_LAT,
                START_LON,
                rawInput
        ));
        return successResult(testId, rawInput, note, response, elapsedMicros(startedNanos));
    }

    private GoldenResult planSuccess(
            String testId,
            String rawInput,
            AiRoutePlanRequest request,
            String note
    ) {
        routingClient.next(BicycleRoutingProviderResult.success("FAKE_GRAPHHOPPER", List.of(candidate())));
        long startedNanos = System.nanoTime();
        AiRoutePlanResponse response = plannerService.plan("synthetic-user", request);
        return successResult(testId, rawInput, note, response, elapsedMicros(startedNanos));
    }

    private GoldenResult successResult(
            String testId,
            String rawInput,
            String note,
            AiRoutePlanResponse response,
            long latencyMicros
    ) {
        BicycleRouteRequest normalized = routingClient.lastRequest();
        BicycleRoutePreference preference = normalized.routePreference();
        return new GoldenResult(
                testId,
                rawInput,
                preference.routePriority(),
                preference.elevationPreference(),
                preference.textIntent(),
                List.of(),
                preference.graphHopperCustomModelJson(),
                routingClient.callsForLastScenario(),
                candidate().distanceMeters(),
                response.routePoints().size(),
                response.recommendationScore(),
                response.scoreBreakdown().total(),
                response.evidenceBadges().stream().map(badge -> badge.source() + ":" + badge.status()).toList(),
                response.elevationStatus(),
                response.sceneryEvidenceStatus(),
                response.routingMetadata() != null && response.routingMetadata().fallbackUsed(),
                response.aiWorkerMetadata().fallbackUsed(),
                response.aiWorkerMetadata().fallbackReason(),
                200,
                response.status(),
                latencyMicros,
                note
        );
    }

    private GoldenResult planFailure(
            String testId,
            String rawInput,
            BicycleRoutingProviderResult providerResult,
            String note
    ) {
        routingClient.next(providerResult);
        long startedNanos = System.nanoTime();
        String resultStatus = "UNEXPECTED_SUCCESS";
        try {
            plannerService.plan("synthetic-user", request(
                    new BigDecimal("37.5100000"), new BigDecimal("127.0100000"),
                    "BALANCED", null, null));
        } catch (BadRequestException expected) {
            resultStatus = "BAD_REQUEST";
        }
        BicycleRoutePreference preference = routingClient.lastRequest().routePreference();
        return new GoldenResult(
                testId, rawInput, preference.routePriority(), preference.elevationPreference(), preference.textIntent(),
                List.of(), preference.graphHopperCustomModelJson(), routingClient.callsForLastScenario(),
                null, 0, null, null, List.of(), null, null, false, false, null,
                400, resultStatus, elapsedMicros(startedNanos), note
        );
    }

    private GoldenResult quotaFailure() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T01:00:00Z"), ZoneOffset.UTC);
        AiRouteQuotaService quotaService = new AiRouteQuotaService(
                clock,
                new InMemoryFixedWindowRateLimiter(clock),
                2,
                20,
                3
        );
        quotaService.checkAllowed("synthetic-user");
        quotaService.checkAllowed("synthetic-user");
        int httpStatus = 200;
        String status = "UNEXPECTED_SUCCESS";
        try {
            quotaService.checkAllowed("synthetic-user");
        } catch (TooManyRequestsException expected) {
            httpStatus = 429;
            status = "TOO_MANY_REQUESTS";
        }
        return new GoldenResult(
                "AI-13", "quota/rate limit", null, null, null, List.of(), null, 0,
                null, 0, null, null, List.of(), null, null, false, false, null,
                httpStatus, status, 0, "third request exceeds deterministic per-minute limit of two"
        );
    }

    private AiRoutePlanRequest request(
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String rideStyle,
            String elevationPreference,
            String textIntent
    ) {
        return new AiRoutePlanRequest(
                START_LAT,
                START_LON,
                destinationLat,
                destinationLon,
                destinationLat == null ? "current-location default" : "synthetic destination",
                rideStyle,
                elevationPreference,
                textIntent
        );
    }

    private BicycleRouteCandidate candidate() {
        return new BicycleRouteCandidate(
                "BALANCED",
                1200,
                360,
                List.of(
                        new BicycleRoutePoint(START_LAT, START_LON, "synthetic start"),
                        new BicycleRoutePoint(new BigDecimal("37.5100000"), new BigDecimal("127.0100000"), "synthetic end")
                ),
                "deterministic fake provider route",
                80,
                70
        );
    }

    private long elapsedMicros(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000;
    }

    private GoldenResult result(List<GoldenResult> results, String testId) {
        return results.stream().filter(result -> result.testId().equals(testId)).findFirst().orElseThrow();
    }

    private static class GoldenRoutingClient implements BicycleRoutingClient {

        private BicycleRoutingProviderResult nextResult;
        private BicycleRouteRequest lastRequest;
        private int scenarioCalls;

        void next(BicycleRoutingProviderResult result) {
            nextResult = result;
            scenarioCalls = 0;
        }

        BicycleRouteRequest lastRequest() {
            return lastRequest;
        }

        int callsForLastScenario() {
            return scenarioCalls;
        }

        @Override
        public BicycleRoutingProviderResult route(BicycleRouteRequest request) {
            lastRequest = request;
            scenarioCalls++;
            return nextResult;
        }
    }

    private record GoldenEvidence(
            String testId,
            String commit,
            String fixtureNotice,
            int caseCount,
            List<GoldenResult> results
    ) {
    }

    private record GoldenResult(
            String testId,
            String rawInput,
            String routePriority,
            String elevationPreference,
            String textIntent,
            List<String> hardFilters,
            String softWeightModel,
            int providerCallCount,
            Integer candidateDistanceM,
            int routePointCount,
            Integer recommendationScore,
            Integer scoreBreakdownTotal,
            List<String> evidenceStatuses,
            String elevationStatus,
            String sceneryEvidenceStatus,
            boolean routingFallbackUsed,
            boolean aiWorkerFallbackUsed,
            String fallbackReason,
            int httpStatus,
            String resultStatus,
            long latencyMicros,
            String note
    ) {
    }
}
