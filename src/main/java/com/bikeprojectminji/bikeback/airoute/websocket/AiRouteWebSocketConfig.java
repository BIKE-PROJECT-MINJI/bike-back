package com.bikeprojectminji.bikeback.airoute.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AiRouteWebSocketConfig implements WebSocketConfigurer {

    private final AiRouteWebSocketHandler aiRouteWebSocketHandler;

    public AiRouteWebSocketConfig(AiRouteWebSocketHandler aiRouteWebSocketHandler) {
        this.aiRouteWebSocketHandler = aiRouteWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(aiRouteWebSocketHandler, "/ws/v1/ai-routes")
                .setAllowedOrigins("*");
    }
}
