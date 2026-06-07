package com.bikeprojectminji.bikeback.airoute.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteConditionContext;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteWorkerClient;
import com.bikeprojectminji.bikeback.airoute.service.CurrentWeatherLookup;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "routing.bicycle.provider=fake",
        "routing.bicycle.fake.enabled=true"
})
class AiRouteContractSmokeTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    @DisplayName("REST와 WebSocket AI 경로 응답은 큐레이터 점수와 evidence 계약을 함께 반환한다")
    void restAndWebSocketReturnSameCuratorScoreContract() throws Exception {
        String requestJson = """
                {
                  "lat": 37.5665,
                  "lon": 126.9780,
                  "destinationLat": 37.5796,
                  "destinationLon": 126.9770,
                  "destinationLabel": "북악스카이웨이",
                  "rideStyle": "SCENERY_FIRST"
                }
                """;

        JsonNode restData = postRestPlan(requestJson);
        JsonNode webSocketData = requestWebSocketPlan(requestJson);

        assertCuratorContract(restData);
        assertCuratorContract(webSocketData);
        assertThat(webSocketData.path("scoreBreakdown").path("total").asInt())
                .isEqualTo(restData.path("scoreBreakdown").path("total").asInt());
        assertThat(webSocketData.path("evidenceBadges").size())
                .isEqualTo(restData.path("evidenceBadges").size());
    }

    private JsonNode postRestPlan(String requestJson) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken());
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/ai-routes/plan",
                new HttpEntity<>(objectMapper.readTree(requestJson), headers),
                String.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("REST response status=%s body=%s", response.getStatusCode(), response.getBody())
                .isTrue();
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        return body.path("data");
    }

    private JsonNode requestWebSocketPlan(String requestJson) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<JsonNode> planData = new AtomicReference<>();
        AtomicReference<String> errorMessage = new AtomicReference<>();

        StandardWebSocketClient client = new StandardWebSocketClient();
        URI uri = URI.create("ws://localhost:" + port + "/ws/v1/ai-routes");
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken());
        client.execute(new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(requestJson));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                JsonNode payload = objectMapper.readTree(message.getPayload());
                if ("plan".equals(payload.path("type").asText())) {
                    planData.set(payload.path("data"));
                    latch.countDown();
                }
                if ("error".equals(payload.path("type").asText())) {
                    errorMessage.set(payload.path("message").asText());
                    latch.countDown();
                }
            }
        }, headers, uri).get(3, TimeUnit.SECONDS);

        assertThat(latch.await(3, TimeUnit.SECONDS))
                .as("WebSocket plan message, error=%s", errorMessage.get())
                .isTrue();
        assertThat(errorMessage.get()).isNull();
        assertThat(planData.get()).isNotNull();
        return planData.get();
    }

    private String accessToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("bike-back-test")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .subject("1")
                .claim("tokenType", "access")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
    }

    private void assertCuratorContract(JsonNode data) {
        assertThat(data.path("recommendationScore").isInt()).isTrue();
        assertThat(data.path("scoreBreakdown").path("total").asInt())
                .isEqualTo(data.path("recommendationScore").asInt());
        assertThat(data.path("scoreBreakdown").path("scenery").isInt()).isTrue();
        assertThat(data.path("scoreBreakdown").path("bikePath").isInt()).isTrue();
        assertThat(data.path("explanation").path("headline").asText()).isNotBlank();
        assertThat(data.path("explanation").path("reason").asText()).isNotBlank();
        assertThat(data.path("explanation").path("caution").asText()).contains("확인");
        assertThat(data.path("explanation").path("nextAction").asText()).isEqualTo("이 경로로 출발");
        assertThat(data.path("evidenceBadges").isArray()).isTrue();
        assertThat(data.path("evidenceBadges").size()).isGreaterThanOrEqualTo(3);
        assertThat(data.path("evidenceBadges").findValuesAsText("status")).contains("UNKNOWN");
        assertThat(data.path("evidenceBadges").findValuesAsText("source"))
                .contains("weather", "construction", "surface");
    }

    @TestConfiguration
    static class ContractSmokeTestConfig {

        @Bean
        @Primary
        CurrentWeatherLookup contractSmokeWeatherLookup() {
            return (lat, lon) -> Optional.of(new CurrentWeatherResponse(
                    new WeatherData(17, "맑음", "none"),
                    new WindData(12, "북서", 315),
                    false,
                    false
            ));
        }

        @Bean
        @Primary
        AiRouteWorkerClient contractSmokeWorkerClient() {
            return (AiRoutePlanRequest request, AiRouteConditionContext context, AiRoutePlanResponse fallbackPlan) ->
                    Optional.empty();
        }
    }
}
