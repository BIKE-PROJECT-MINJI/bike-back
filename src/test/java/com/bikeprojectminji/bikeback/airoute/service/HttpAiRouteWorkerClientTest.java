package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpAiRouteWorkerClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HTTP worker client는 backend 점수와 evidence를 worker payload로 전달한다")
    void sendBackendScoreAndEvidenceToWorker() throws Exception {
        CapturedExchange capturedExchange = startWorkerServer(200, workerSuccessResponse());
        HttpAiRouteWorkerClient client = new HttpAiRouteWorkerClient("http://localhost:" + server.getAddress().getPort());
        AiRoutePlanResponse fallbackPlan = fallbackPlan();

        Optional<AiRoutePlanResponse> response = client.plan(request(), context(), fallbackPlan);

        assertThat(response).isPresent();
        assertThat(response.get().aiGenerated()).isTrue();
        JsonNode payload = objectMapper.readTree(capturedExchange.body());
        assertThat(payload.path("recommendationScore").asInt()).isEqualTo(fallbackPlan.recommendationScore());
        assertThat(payload.path("scoreBreakdown").path("total").asInt()).isEqualTo(fallbackPlan.recommendationScore());
        assertThat(payload.path("evidenceBadges").findValuesAsText("source"))
                .contains("weather", "construction", "surface");
        assertThat(payload.path("evidenceBadges").findValuesAsText("status"))
                .contains("VERIFIED", "UNKNOWN");
        assertThat(payload.path("fallbackPlan").path("explanation").path("caution").asText())
                .contains("확인");
    }

    @Test
    @DisplayName("HTTP worker client는 worker 장애를 Optional.empty로 변환해 fallback을 보존한다")
    void returnEmptyWhenWorkerFails() throws Exception {
        startWorkerServer(500, "{\"message\":\"fail\"}");
        HttpAiRouteWorkerClient client = new HttpAiRouteWorkerClient("http://localhost:" + server.getAddress().getPort());

        Optional<AiRoutePlanResponse> response = client.plan(request(), context(), fallbackPlan());

        assertThat(response).isEmpty();
    }

    private CapturedExchange startWorkerServer(int status, String responseBody) throws IOException {
        CapturedExchange capturedExchange = new CapturedExchange();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/ai-routes/plan", exchange -> {
            capturedExchange.capture(exchange);
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return capturedExchange;
    }

    private String workerSuccessResponse() {
        return """
                {
                  "planId": "worker-plan-1",
                  "status": "READY",
                  "summary": "북악스카이웨이까지 경치 우선 경로입니다.",
                  "confidence": "medium",
                  "weather": {"temperatureC": 17, "sky": "맑음", "precipType": "none"},
                  "wind": {"speedKmh": 12, "directionText": "북서", "directionDeg": 315},
                  "routePoints": [
                    {"lat": 37.5665, "lon": 126.9780, "label": "현재 위치"},
                    {"lat": 37.5730, "lon": 126.9775, "label": "전망 구간"},
                    {"lat": 37.5796, "lon": 126.9770, "label": "북악스카이웨이"}
                  ],
                  "risks": [],
                  "actions": ["출발 전 현장 표지를 확인하세요."],
                  "recommendationScore": 82,
                  "scoreBreakdown": {
                    "total": 82,
                    "scenery": 91,
                    "bikePath": 78,
                    "safety": 72,
                    "condition": 68,
                    "preferenceFit": 88,
                    "distancePenalty": 4,
                    "unknownPenalty": 12
                  },
                  "explanation": {
                    "headline": "북악스카이웨이까지 경치 우선 자전거 여행길을 골랐어요.",
                    "reason": "추천점수 82점, 경치 91점, 자전거도로 78점 기준입니다.",
                    "caution": "공사 정보는 아직 확인되지 않았고, 노면 provider 확인 실패가 있어 출발 전 현장 표지를 확인하세요.",
                    "nextAction": "이 경로로 출발"
                  },
                  "evidenceBadges": [
                    {"source": "weather", "label": "날씨", "status": "VERIFIED", "severity": "INFO", "summary": "맑음", "observedAt": null},
                    {"source": "roadwork", "label": "공사", "status": "UNKNOWN", "severity": "UNKNOWN", "summary": "공사 정보 미확인", "observedAt": null},
                    {"source": "surface", "label": "노면", "status": "FAILED", "severity": "UNKNOWN", "summary": "노면 provider 확인 실패", "observedAt": null}
                  ],
                  "aiGenerated": true
                }
                """;
    }

    private AiRoutePlanRequest request() {
        return new AiRoutePlanRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.5796),
                BigDecimal.valueOf(126.9770),
                "북악스카이웨이",
                "SCENERY_FIRST"
        );
    }

    private AiRouteConditionContext context() {
        return new AiRouteConditionContext(
                Optional.of(new CurrentWeatherResponse(
                        new WeatherData(17, "맑음", "none"),
                        new WindData(12, "북서", 315),
                        false,
                        false
                )),
                "공사 정보 미확인",
                "노면 provider 확인 실패"
        );
    }

    private AiRoutePlanResponse fallbackPlan() {
        return new AiRoutePlanComposer().composeFallback(request(), context());
    }

    private static class CapturedExchange {

        private String body;

        void capture(HttpExchange exchange) throws IOException {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        String body() {
            return body;
        }
    }
}
