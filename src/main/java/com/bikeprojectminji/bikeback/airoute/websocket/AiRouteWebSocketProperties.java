package com.bikeprojectminji.bikeback.airoute.websocket;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai-route.websocket")
public class AiRouteWebSocketProperties {

    private static final List<String> LOCAL_DEVELOPMENT_ALLOWED_ORIGINS = List.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:8080",
            "http://localhost:8081",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:8080",
            "http://127.0.0.1:8081"
    );

    private List<String> allowedOrigins = LOCAL_DEVELOPMENT_ALLOWED_ORIGINS;

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    String[] allowedOriginsArray() {
        List<String> normalizedOrigins = normalizedAllowedOrigins();
        return normalizedOrigins.toArray(String[]::new);
    }

    private List<String> normalizedAllowedOrigins() {
        List<String> normalized = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
        // 설정이 비어 있으면 브라우저 WebSocket 테스트가 가능한 로컬 포트만 기본 허용한다.
        return normalized.isEmpty() ? LOCAL_DEVELOPMENT_ALLOWED_ORIGINS : normalized;
    }
}
