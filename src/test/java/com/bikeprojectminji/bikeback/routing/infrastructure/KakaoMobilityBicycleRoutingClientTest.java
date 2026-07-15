package com.bikeprojectminji.bikeback.routing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingFailureCause;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoMobilityBicycleRoutingClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Kakao Mobility 정상 응답에 경로가 없으면 no route 원인을 보존한다")
    void routePreservesNoRouteCauseForEmptyRoutes() throws IOException {
        startServer(200, """
                {"routes":[]}
                """);

        BicycleRoutingProviderResult result = client().route(request());

        assertThat(result.status()).isEqualTo("NO_ROUTE");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.NO_ROUTE);
        assertThat(result.retryAfterSeconds()).isNull();
    }

    @Test
    @DisplayName("Kakao Mobility 429는 quota 원인과 재시도 정보로 보존한다")
    void routePreservesQuotaCauseForRateLimit() throws IOException {
        startServer(429, """
                {"message":"rate limit"}
                """, Map.of("Retry-After", "19"));

        BicycleRoutingProviderResult result = client().route(request());

        assertThat(result.status()).isEqualTo("QUOTA_EXCEEDED");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.QUOTA_EXCEEDED);
        assertThat(result.retryAfterSeconds()).isEqualTo(19);
    }

    @Test
    @DisplayName("Kakao Mobility 비 quota HTTP 오류는 provider 장애로 보존한다")
    void routePreservesProviderUnavailableCauseForHttpFailure() throws IOException {
        startServer(503, """
                {"message":"temporarily unavailable"}
                """, Map.of("Retry-After", "13"));

        BicycleRoutingProviderResult result = client().route(request());

        assertThat(result.status()).isEqualTo("PROVIDER_FAILURE");
        assertThat(result.failureCause()).isEqualTo(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE);
        assertThat(result.retryAfterSeconds()).isEqualTo(13);
    }

    private KakaoMobilityBicycleRoutingClient client() {
        return new KakaoMobilityBicycleRoutingClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-key"
        );
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

    private void startServer(int statusCode, String body) throws IOException {
        startServer(statusCode, body, Map.of());
    }

    private void startServer(int statusCode, String body, Map<String, String> responseHeaders) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/affiliate/bicycle/v1/directions", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.sendResponseHeaders(statusCode, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }
}
