package com.bikeprojectminji.bikeback.airoute.websocket;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class AiRouteWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AiRoutePlannerService aiRoutePlannerService;
    private final AiRouteQuotaService aiRouteQuotaService;

    public AiRouteWebSocketHandler(
            ObjectMapper objectMapper,
            AiRoutePlannerService aiRoutePlannerService,
            AiRouteQuotaService aiRouteQuotaService
    ) {
        this.objectMapper = objectMapper;
        this.aiRoutePlannerService = aiRoutePlannerService;
        this.aiRouteQuotaService = aiRouteQuotaService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        session.sendMessage(toMessage(Map.of("type", "accepted")));
        try {
            AiRoutePlanRequest request = objectMapper.readValue(message.getPayload(), AiRoutePlanRequest.class);
            aiRouteQuotaService.checkAllowed(subject(session));
            AiRoutePlanResponse response = aiRoutePlannerService.plan(request);
            session.sendMessage(toMessage(Map.of("type", "plan", "data", response)));
        } catch (TooManyRequestsException exception) {
            session.sendMessage(toMessage(Map.of(
                    "type", "error",
                    "code", "RATE_LIMITED",
                    "message", exception.getMessage()
            )));
        } catch (RuntimeException exception) {
            session.sendMessage(toMessage(Map.of(
                    "type", "error",
                    "message", exception.getMessage() == null ? "AI 경로를 생성하지 못했습니다." : exception.getMessage()
            )));
        } finally {
            session.close(CloseStatus.NORMAL);
        }
    }

    private TextMessage toMessage(Object payload) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(payload));
    }

    private String subject(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        return principal == null ? "" : principal.getName();
    }
}
