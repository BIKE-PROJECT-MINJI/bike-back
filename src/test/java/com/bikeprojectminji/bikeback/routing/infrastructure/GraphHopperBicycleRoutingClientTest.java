package com.bikeprojectminji.bikeback.routing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                      "points": {
                        "type": "LineString",
                        "coordinates": [
                          [126.9780000, 37.5665000],
                          [126.9820000, 37.5700000],
                          [126.9900000, 37.5750000]
                        ]
                      },
                      "details": {
                        "bike_network": [[0, 1, "local"], [1, 2, "regional"]],
                        "surface": [[0, 2, "asphalt"]],
                        "road_environment": [[0, 1, "road"], [1, 2, "bridge"]],
                        "road_class": [[0, 2, "cycleway"]]
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
        assertThat(result.candidates().get(0).bikePathScore()).isGreaterThanOrEqualTo(85);
        assertThat(result.candidates().get(0).evidenceSummary()).contains("cycleway", "asphalt", "regional");
        assertThat(result.candidates().get(0).evidenceBadges())
                .extracting("source")
                .contains("graphhopper.road_class", "graphhopper.surface", "graphhopper.smoothness");
        assertThat(result.candidates().get(0).evidenceBadges())
                .extracting("status")
                .contains("VERIFIED", "WARNING", "UNKNOWN");
    }

    @Test
    @DisplayName("GraphHopper provider는 4xx/5xx 응답을 provider failure로 변환한다")
    void routeReturnsProviderFailureOnGraphHopperError() throws IOException {
        startServer(429, """
                {"message":"rate limit"}
                """);
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

        assertThat(result.status()).isEqualTo("PROVIDER_FAILURE");
        assertThat(result.provider()).isEqualTo("GRAPHHOPPER");
        assertThat(result.candidates()).isEmpty();
        assertThat(meterRegistry.get("bike_routing_provider_failure_total")
                .tag("provider", "graphhopper")
                .tag("reason", "http_429")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GraphHopper provider는 self-host 실패를 재시도한 뒤 hosted GraphHopper로 대체한다")
    void routeRetriesSelfHostedThenUsesHostedGraphHopper() throws IOException {
        AtomicInteger selfHostedHits = new AtomicInteger();
        HttpServer selfHostedServer = startServer(500, """
                {"message":"self-host unavailable"}
                """, selfHostedHits);
        HttpServer hostedServer = startServer(200, """
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
                """);

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

        assertThat(selfHostedHits).hasValue(2);
        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.provider()).isEqualTo("GRAPHHOPPER");
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).polyline()).hasSize(2);
    }

    private HttpServer startServer(int statusCode, String body) throws IOException {
        return startServer(statusCode, body, new AtomicInteger());
    }

    private HttpServer startServer(int statusCode, String body, AtomicInteger hitCounter) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.createContext("/route", exchange -> {
            hitCounter.incrementAndGet();
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
}
