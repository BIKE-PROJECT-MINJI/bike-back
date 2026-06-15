package com.bikeprojectminji.bikeback.airoute.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AiRouteWebSocketConfig implements WebSocketConfigurer {

    private final AiRouteWebSocketHandler aiRouteWebSocketHandler;
    private final AiRouteWebSocketProperties properties;

    public AiRouteWebSocketConfig(
            AiRouteWebSocketHandler aiRouteWebSocketHandler,
            AiRouteWebSocketProperties properties
    ) {
        this.aiRouteWebSocketHandler = aiRouteWebSocketHandler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // WebSocket은 브라우저 Origin 검사를 받으므로 운영에서는 프론트 도메인만 설정한다.
        registry.addHandler(aiRouteWebSocketHandler, "/ws/v1/ai-routes")
                .setAllowedOrigins(properties.allowedOriginsArray());
    }
}
