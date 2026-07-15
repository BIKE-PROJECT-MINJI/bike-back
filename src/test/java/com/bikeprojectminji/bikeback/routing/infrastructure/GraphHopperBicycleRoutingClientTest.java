package com.bikeprojectminji.bikeback.routing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingFailureCause;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GraphHopperBicycleRoutingClientTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    @DisplayName("GraphHopper provider는 path detail과 LineString을 자전거 경로 후보로 변환한다")
    void routeMapsGraphHopperPathDetails() throws IOException {
        startServer(200, """
                {
                  "paths": [
                    {
                      "distance": 1234.5,
                      "time": 420000,
                      "ascend": 88.4,
                      "descend": 21.2,
                      "points": {
                        "type": "LineString",
                        "coordinates": [
                          [126.9780000, 37.5665000, 35.0],
                          [126.9820000, 37.5700000, 82.0],
                          [126.9900000, 37.5750000, 123.0]
                        ]
                      },
                      "details": {
                        "bike_network": [[0, 1, "local"], [1, 2, "regional"]],
                        "surface": [[0, 2, "asphalt"]],
                        "road_environment": [[0, 1, "road"], [1, 2, "bridge"]],
                        "road_class": [[0, 2, "cycleway"]],
                        "average_slope": [[0, 1, 4.2], [1, 2, 7.8]],
                        "max_slope": [[0, 1, 9.0], [1, 2, 12.5]]
                      }
                    }
                  ]
                }
                """);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(baseUrl(), "");

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5750),
                BigDecimal.valueOf(126.9900),
                "BIKE_PATH_FIRST"
        ));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.provider()).isEqualTo("GRAPHHOPPER");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).routeType()).isEqualTo("BIKE_PATH");
        assertThat(result.candidates().get(0).distanceMeters()).isEqualTo(1235);
        assertThat(result.candidates().get(0).durationSeconds()).isEqualTo(420);
        assertThat(result.candidates().get(0).polyline()).hasSize(3);
        assertThat(result.candidates().get(0).polyline().get(2).altitudeM()).isEqualByComparingTo("123.0");
        assertThat(result.candidates().get(0).elevationSummary().totalAscentM()).isEqualByComparingTo("88.4");
        assertThat(result.candidates().get(0).elevationSummary().totalDescentM()).isEqualByComparingTo("21.2");
        assertThat(result.candidates().get(0).elevationSummary().minAltitudeM()).isEqualByComparingTo("35.0");
        assertThat(result.candidates().get(0).elevationSummary().maxAltitudeM()).isEqualByComparingTo("123.0");
        assertThat(result.candidates().get(0).elevationSummary().maxSlopePercent()).isEqualByComparingTo("12.5");
        assertThat(result.candidates().get(0).bikePathScore()).isGreaterThanOrEqualTo(85);
        assertThat(result.candidates().get(0).evidenceSummary()).contains("cycleway", "asphalt", "regional");
        assertThat(result.candidates().get(0).evidenceBadges())
                .extracting("source")
                .contains("graphhopper.road_class", "graphhopper.surface", "graphhopper.smoothness", "graphhopper.elevation", "graphhopper.slope");
        assertThat(result.candidates().get(0).evidenceBadges())
                .extracting("status")
                .contains("VERIFIED", "WARNING", "UNKNOWN");
    }

    @Test
    @DisplayName("GraphHopper provider는 429 응답을 quota 원인과 재시도 정보로 보존한다")
    void routeReturnsProviderFailureOnGraphHopperError() throws IOException {
        startServer(429, """
                {"message":"rate limit"}
                """, Map.of("Retry-After", "17"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                baseUrl(),
                "api-key",
                new BikeMetricsRecorder(meterRegistry)
        );

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5750),
                BigDecimal.valueOf(126.9900),
                "SCENERY_FIRST"
        ));

        assertThat(result.status()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.QUOTA_EXCEEDED);
        assertThat(result.retryAfterSeconds()).isEqualTo(17);
        assertThat(result.provider()).isEqualTo("GRAPHHOPPER");
        assertThat(result.candidates()).isEmpty();
        assertThat(meterRegistry.get("bike_routing_provider_failure_total")
                .tag("provider", "graphhopper")
                .tag("reason", "http_429")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GraphHopper provider는 정상 응답에 경로가 없으면 장애가 아니라 no route로 보존한다")
    void routePreservesNoRouteCauseForEmptyPaths() throws IOException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        startServer(200, """
                {"paths":[]}
                """);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                baseUrl(), "", new BikeMetricsRecorder(meterRegistry));

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5750),
                BigDecimal.valueOf(126.9900),
                "SCENERY_FIRST"
        ));

        assertThat(result.status()).isEqualTo("NO_ROUTE");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.NO_ROUTE);
        assertThat(result.retryAfterSeconds()).isNull();
        assertThat(meterRegistry.find("bike_routing_provider_failure_total").counter()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {502, 503, 504})
    @DisplayName("GraphHopper 502/503/504는 provider 장애와 Retry-After를 보존한다")
    void routePreservesProviderUnavailableCauseForGatewayFailure(int statusCode) throws IOException {
        startServer(statusCode, """
                {"message":"temporarily unavailable"}
                """, Map.of("Retry-After", "11"));

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(baseUrl(), "");

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5750),
                BigDecimal.valueOf(126.9900),
                "SCENERY_FIRST"
        ));

        assertThat(result.status()).isEqualTo("PROVIDER_FAILURE");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE);
        assertThat(result.retryAfterSeconds()).isEqualTo(11);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404})
    @DisplayName("GraphHopper upstream 400/404는 입력 검증 오류가 아니라 provider 계약 장애로 보존한다")
    void routeTreatsUpstreamClientErrorsAsProviderContractFailure(int statusCode) throws IOException {
        startServer(statusCode, """
                {"message":"route endpoint not found"}
                """);

        BicycleRoutingProviderResult result = new GraphHopperBicycleRoutingClient(baseUrl(), "").route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5750), BigDecimal.valueOf(126.9900), "SCENERY_FIRST"));

        assertThat(result.status()).isEqualTo("PROVIDER_FAILURE");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("Retry-After HTTP-date는 표준 시각 파서로 남은 초를 계산한다")
    void retryAfterParserSupportsHttpDate() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Retry-After", "Wed, 15 Jul 2026 09:01:30 GMT");
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T09:00:00Z"), ZoneOffset.UTC);

        assertThat(ProviderRetryAfterParser.secondsOrDefault(headers, 3, clock)).isEqualTo(90);
    }

    @Test
    @DisplayName("GraphHopper provider는 null detail/coordinate 구간을 건너뛰고 유효 경로를 반환한다")
    void routeSkipsMalformedGraphHopperSegments() throws IOException {
        startServer(200, """
                {
                  "paths": [
                    {
                      "distance": 1000.0,
                      "time": 300000,
                      "points": {
                        "type": "LineString",
                        "coordinates": [
                          null,
                          [126.9500000],
                          [126.9600000, 37.4900000]
                        ]
                      },
                      "details": {
                        "average_slope": [null, [0, 1, 11.0]]
                      }
                    }
                  ]
                }
                """);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(baseUrl(), "");

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.4800),
                BigDecimal.valueOf(126.9500),
                BigDecimal.valueOf(37.4900),
                BigDecimal.valueOf(126.9600),
                "SCENERY_FIRST"
        ));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).polyline()).hasSize(1);
        assertThat(result.candidates().get(0).evidenceBadges())
                .filteredOn(badge -> "graphhopper.slope".equals(badge.source()))
                .extracting("status")
                .containsExactly("WARNING");
    }

    @Test
    @DisplayName("GraphHopper provider는 여러 base URL에서 self-host를 반복하지 않고 hosted로 즉시 대체한다")
    void routeMovesDirectlyFromSelfHostedFailureToHostedGraphHopper() throws IOException {
        AtomicInteger selfHostedHits = new AtomicInteger();
        AtomicInteger hostedHits = new AtomicInteger();
        List<String> callOrder = new CopyOnWriteArrayList<>();
        HttpServer selfHostedServer = startSequencedServer(503, """
                {"message":"self-host unavailable"}
                """, selfHostedHits, callOrder, "self-host");
        HttpServer hostedServer = startSequencedServer(
                200, minimalSuccessBody(), hostedHits, callOrder, "hosted");

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                List.of(baseUrl(selfHostedServer), baseUrl(hostedServer)),
                "",
                2,
                null
        );

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.4800),
                BigDecimal.valueOf(126.9500),
                BigDecimal.valueOf(37.4900),
                BigDecimal.valueOf(126.9600),
                "BIKE_PATH_FIRST"
        ));

        assertThat(selfHostedHits).hasValue(1);
        assertThat(hostedHits).hasValue(1);
        assertThat(callOrder).containsExactly("self-host", "hosted");
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.provider()).isEqualTo("GRAPHHOPPER");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.fallbackReason()).contains("provider 장애", "hosted GraphHopper");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).polyline()).hasSize(2);
    }

    @Test
    @DisplayName("GraphHopper provider는 단일 base URL일 때만 retryMaxAttempts를 적용한다")
    void routeRetriesRetryableFailureOnlyForSingleBaseUrl() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startServer(503, """
                {"message":"temporarily unavailable"}
                """, hits);
        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                List.of(baseUrl(server)), "", 3, null);

        BicycleRoutingProviderResult result = client.route(request());

        assertThat(hits).hasValue(3);
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429})
    @DisplayName("GraphHopper 400/404/429는 동일 endpoint를 반복하지 않고 다음 base URL로 이동한다")
    void routeDoesNotRetryNonRetryableHttpFailuresOnSameEndpoint(int statusCode) throws IOException {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger hostedHits = new AtomicInteger();
        HttpServer primaryServer = startServer(statusCode, """
                {"message":"non retryable"}
                """, primaryHits);
        HttpServer hostedServer = startServer(200, minimalSuccessBody(), hostedHits);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                List.of(baseUrl(primaryServer), baseUrl(hostedServer)), "", 3, null);

        BicycleRoutingProviderResult result = client.route(request());

        assertThat(primaryHits).hasValue(1);
        assertThat(hostedHits).hasValue(1);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("GraphHopper 빈 경로는 동일 endpoint를 반복하지 않고 다음 base URL로 이동한다")
    void routeDoesNotRetryNoRouteOnSameEndpoint() throws IOException {
        AtomicInteger primaryHits = new AtomicInteger();
        AtomicInteger hostedHits = new AtomicInteger();
        HttpServer primaryServer = startServer(200, """
                {"paths":[]}
                """, primaryHits);
        HttpServer hostedServer = startServer(200, minimalSuccessBody(), hostedHits);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                List.of(baseUrl(primaryServer), baseUrl(hostedServer)), "", 3, null);

        BicycleRoutingProviderResult result = client.route(request());

        assertThat(primaryHits).hasValue(1);
        assertThat(hostedHits).hasValue(1);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.fallbackReason()).contains("경로 없음");
    }

    @Test
    @DisplayName("GraphHopper self-host와 hosted 혼합 실패도 provider 장애가 quota보다 우선한다")
    void routeAggregatesProviderFailureBeforeQuotaAcrossBaseUrls() throws IOException {
        HttpServer selfHostedServer = startServer(503, """
                {"message":"self-host unavailable"}
                """, Map.of("Retry-After", "11"));
        HttpServer hostedServer = startServer(429, """
                {"message":"hosted quota"}
                """, Map.of("Retry-After", "17"));

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                List.of(baseUrl(selfHostedServer), baseUrl(hostedServer)), "", 1, null);

        BicycleRoutingProviderResult result = client.route(request());

        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE);
        assertThat(result.retryAfterSeconds()).isEqualTo(11);
    }

    @Test
    @DisplayName("GraphHopper 체인에서 정상 provider의 빈 경로는 다른 endpoint 장애보다 우선한다")
    void routeAggregatesNoRouteBeforeProviderFailureAcrossBaseUrls() throws IOException {
        HttpServer selfHostedServer = startServer(503, """
                {"message":"self-host unavailable"}
                """);
        HttpServer hostedServer = startServer(200, """
                {"paths":[]}
                """);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(
                List.of(baseUrl(selfHostedServer), baseUrl(hostedServer)), "", 1, null);

        BicycleRoutingProviderResult result = client.route(request());

        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.NO_ROUTE);
        assertThat(result.retryAfterSeconds()).isNull();
    }

    @Test
    @DisplayName("GraphHopper provider는 선호도에 맞춘 custom_model 힌트를 요청에 포함한다")
    void routeIncludesPreferenceCustomModel() throws IOException {
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        startServer(200, """
                {
                  "paths": [
                    {
                      "distance": 2100.0,
                      "time": 600000,
                      "points": {
                        "type": "LineString",
                        "coordinates": [
                          [126.9500000, 37.4800000],
                          [126.9600000, 37.4900000]
                        ]
                      },
                      "details": {
                        "road_class": [[0, 1, "cycleway"]],
                        "surface": [[0, 1, "asphalt"]]
                      }
                    }
                  ]
                }
                """, new AtomicInteger(), capturedQuery);

        GraphHopperBicycleRoutingClient client = new GraphHopperBicycleRoutingClient(baseUrl(), "");

        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.4800),
                BigDecimal.valueOf(126.9500),
                BigDecimal.valueOf(37.4900),
                BigDecimal.valueOf(126.9600),
                "SCENERY_FIRST",
                "FLAT_FIRST",
                "TEXT_FLAT_RIVERSIDE"
        ));

        String decodedQuery = URLDecoder.decode(capturedQuery.get(), StandardCharsets.UTF_8);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(decodedQuery).contains("custom_model");
        assertThat(decodedQuery).contains("bike_network == LOCAL");
        assertThat(decodedQuery).contains("max_slope > 8");
    }

    private HttpServer startServer(int statusCode, String body) throws IOException {
        return startServer(statusCode, body, new AtomicInteger());
    }

    private HttpServer startServer(int statusCode, String body, Map<String, String> responseHeaders) throws IOException {
        return startServer(statusCode, body, new AtomicInteger(), new AtomicReference<>(), responseHeaders);
    }

    private HttpServer startServer(int statusCode, String body, AtomicInteger hitCounter) throws IOException {
        return startServer(statusCode, body, hitCounter, new AtomicReference<>());
    }

    private HttpServer startServer(
            int statusCode,
            String body,
            AtomicInteger hitCounter,
            AtomicReference<String> queryCapture
    ) throws IOException {
        return startServer(statusCode, body, hitCounter, queryCapture, Map.of());
    }

    private HttpServer startServer(
            int statusCode,
            String body,
            AtomicInteger hitCounter,
            AtomicReference<String> queryCapture,
            Map<String, String> responseHeaders
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/route", exchange -> {
            hitCounter.incrementAndGet();
            queryCapture.set(exchange.getRequestURI().getRawQuery());
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        servers.add(httpServer);
        return httpServer;
    }

    private HttpServer startSequencedServer(
            int statusCode,
            String body,
            AtomicInteger hitCounter,
            List<String> callOrder,
            String label
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/route", exchange -> {
            hitCounter.incrementAndGet();
            callOrder.add(label);
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        servers.add(httpServer);
        return httpServer;
    }

    private String baseUrl() {
        return baseUrl(servers.get(servers.size() - 1));
    }

    private String baseUrl(HttpServer httpServer) {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    private BicycleRouteRequest request() {
        return new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5750),
                BigDecimal.valueOf(126.9900),
                "SCENERY_FIRST"
        );
    }

    private String minimalSuccessBody() {
        return """
                {
                  "paths": [{
                    "distance": 2100.0,
                    "time": 600000,
                    "points": {
                      "type": "LineString",
                      "coordinates": [
                        [126.9780, 37.5665],
                        [126.9900, 37.5750]
                      ]
                    },
                    "details": {"road_class": [[0, 1, "cycleway"]]}
                  }]
                }
                """;
    }
}
