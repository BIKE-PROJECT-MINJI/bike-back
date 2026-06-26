package com.bikeprojectminji.bikeback.party.websocket;

import com.bikeprojectminji.bikeback.airoute.websocket.AiRouteWebSocketProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class RidePartyWebSocketConfig implements WebSocketConfigurer {

    private final RidePartyLocationWebSocketHandler locationWebSocketHandler;
    private final AiRouteWebSocketProperties properties;

    public RidePartyWebSocketConfig(
            RidePartyLocationWebSocketHandler locationWebSocketHandler,
            AiRouteWebSocketProperties properties
    ) {
        this.locationWebSocketHandler = locationWebSocketHandler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(locationWebSocketHandler, "/ws/v1/parties/{partyId}/locations")
                .setAllowedOrigins(properties.allowedOriginsArray());
    }
}
