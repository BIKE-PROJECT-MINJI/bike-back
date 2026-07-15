package com.bikeprojectminji.bikeback.airoute.websocket;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.InvalidRouteRequestException;
import com.bikeprojectminji.bikeback.global.exception.RetryableServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.LinkedHashMap;
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
            String subject = subject(session);
            aiRouteQuotaService.checkAllowed(subject);
            AiRoutePlanResponse response = aiRoutePlannerService.plan(subject, request);
            session.sendMessage(toMessage(Map.of("type", "plan", "data", response)));
        } catch (JsonProcessingException exception) {
            session.sendMessage(errorMessage(InvalidRouteRequestException.ERROR_CODE, "요청 형식을 확인해 주세요.", null));
        } catch (RetryableTooManyRequestsException exception) {
            session.sendMessage(errorMessage(exception.getErrorCode(), exception.getMessage(), exception.getRetryAfterSeconds()));
        } catch (TooManyRequestsException exception) {
            session.sendMessage(errorMessage("RATE_LIMITED", exception.getMessage(), null));
        } catch (RetryableServiceUnavailableException exception) {
            session.sendMessage(errorMessage(exception.getErrorCode(), exception.getMessage(), exception.getRetryAfterSeconds()));
        } catch (InvalidRouteRequestException exception) {
            session.sendMessage(errorMessage(InvalidRouteRequestException.ERROR_CODE, exception.getMessage(), null));
        } catch (RouteNotFoundException exception) {
            session.sendMessage(errorMessage(RouteNotFoundException.ERROR_CODE, exception.getMessage(), null));
        } catch (RuntimeException exception) {
            session.sendMessage(errorMessage("INTERNAL_ERROR", "AI 경로를 생성하지 못했습니다.", null));
        } finally {
            session.close(CloseStatus.NORMAL);
        }
    }

    private TextMessage toMessage(Object payload) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(payload));
    }

    private TextMessage errorMessage(String errorCode, String message, Integer retryAfterSeconds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("code", errorCode);
        payload.put("errorCode", errorCode);
        payload.put("message", message);
        if (retryAfterSeconds != null) {
            payload.put("retryAfterSeconds", retryAfterSeconds);
        }
        return toMessage(payload);
    }

    private String subject(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        return principal == null ? "" : principal.getName();
    }
}
