package com.bikeprojectminji.bikeback.airoute.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AiRouteWebSocketPropertiesTest {

    @Test
    @DisplayName("WebSocket origin 설정이 비어 있으면 로컬 개발 origin 기본값을 사용한다")
    void allowedOriginsFallsBackToLocalDefaultsWhenBlank() {
        AiRouteWebSocketProperties properties = new AiRouteWebSocketProperties();
        properties.setAllowedOrigins(List.of(" ", ""));

        assertThat(properties.allowedOriginsArray())
                .contains("http://localhost:8080", "http://127.0.0.1:8081")
                .doesNotContain("*");
    }

    @Test
    @DisplayName("WebSocket origin 설정은 앞뒤 공백을 제거하고 빈 값을 버린다")
    void allowedOriginsTrimsConfiguredValues() {
        AiRouteWebSocketProperties properties = new AiRouteWebSocketProperties();
        properties.setAllowedOrigins(List.of(" https://web.example.com ", "", "https://admin.example.com"));

        assertThat(properties.allowedOriginsArray())
                .containsExactly("https://web.example.com", "https://admin.example.com");
    }

    @Test
    @DisplayName("WebSocket origin 환경변수 형태의 쉼표 문자열은 설정 객체의 List로 바인딩된다")
    void allowedOriginsBindsCommaSeparatedProperty() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "ai-route.websocket.allowed-origins",
                "https://web.example.com,https://admin.example.com"
        ));

        AiRouteWebSocketProperties properties = new Binder(source)
                .bind("ai-route.websocket", AiRouteWebSocketProperties.class)
                .orElseThrow(() -> new AssertionError("WebSocket properties binding failed"));

        assertThat(properties.allowedOriginsArray())
                .containsExactly("https://web.example.com", "https://admin.example.com");
    }
}
