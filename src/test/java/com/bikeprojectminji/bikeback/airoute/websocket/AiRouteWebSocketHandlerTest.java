package com.bikeprojectminji.bikeback.airoute.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.exception.InvalidRouteRequestException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class AiRouteWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiRoutePlannerService plannerService = mock(AiRoutePlannerService.class);
    private final AiRouteQuotaService quotaService = mock(AiRouteQuotaService.class);
    private final TestHandler handler = new TestHandler(objectMapper, plannerService, quotaService);

    @Test
    @DisplayName("WebSocket AI 경로 생성은 quota와 planner에 같은 인증 subject를 전달한다")
    void handleTextMessagePassesAuthenticatedSubjectToPlanner() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Principal principal = () -> "42";
        given(session.getPrincipal()).willReturn(principal);
        given(plannerService.plan(eq("42"), any())).willReturn(minimalResponse());

        handler.handle(session, new TextMessage("""
                {
                  "lat": 37.4812,
                  "lon": 126.9527
                }
                """));

        verify(quotaService).checkAllowed("42");
        verify(plannerService).plan(eq("42"), any());
    }

    @Test
    @DisplayName("WebSocket AI 경로 생성은 내부 예외 메시지를 클라이언트에 노출하지 않는다")
    void handleTextMessageReturnsGenericMessageForUnexpectedError() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Principal principal = () -> "42";
        given(session.getPrincipal()).willReturn(principal);
        given(plannerService.plan(eq("42"), any())).willThrow(new IllegalStateException("secret provider dsn"));

        handler.handle(session, new TextMessage("""
                {
                  "lat": 37.4812,
                  "lon": 126.9527
                }
                """));

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        String errorPayload = messages.getAllValues().get(1).getPayload();
        assertThat(errorPayload).contains("\"type\":\"error\"");
        assertThat(errorPayload).contains("AI 경로를 생성하지 못했습니다.");
        assertThat(errorPayload).doesNotContain("secret provider dsn");
        assertThat(errorPayload).contains("\"errorCode\":\"INTERNAL_ERROR\"");
    }

    @Test
    @DisplayName("WebSocket AI 경로 생성은 typed 422 errorCode를 보존한다")
    void handleTextMessagePreservesRouteNotFoundCode() throws Exception {
        String payload = errorPayload(new RouteNotFoundException("조건을 충족하는 경로가 없습니다."));

        assertThat(payload).contains("\"errorCode\":\"ROUTE_NOT_FOUND\"");
        assertThat(payload).doesNotContain("retryAfterSeconds");
    }

    @Test
    @DisplayName("WebSocket AI 경로 생성은 retryable 429 metadata를 보존한다")
    void handleTextMessagePreservesRoutingQuotaMetadata() throws Exception {
        String payload = errorPayload(new RetryableTooManyRequestsException(
                "라우팅 quota에 도달했습니다.", "ROUTING_QUOTA_EXCEEDED", 7));

        assertThat(payload).contains("\"errorCode\":\"ROUTING_QUOTA_EXCEEDED\"");
        assertThat(payload).contains("\"retryAfterSeconds\":7");
    }

    @Test
    @DisplayName("WebSocket AI 경로 생성은 retryable 503 metadata를 보존한다")
    void handleTextMessagePreservesProviderUnavailableMetadata() throws Exception {
        String payload = errorPayload(new RoutingProviderUnavailableException(
                "라우팅 provider가 일시적으로 불안정합니다.", 9));

        assertThat(payload).contains("\"errorCode\":\"ROUTING_PROVIDER_UNAVAILABLE\"");
        assertThat(payload).contains("\"retryAfterSeconds\":9");
    }

    @Test
    @DisplayName("WebSocket AI 경로 생성은 typed 400 errorCode를 보존한다")
    void handleTextMessagePreservesInvalidRequestCode() throws Exception {
        String payload = errorPayload(new InvalidRouteRequestException("현재 위치가 필요합니다."));

        assertThat(payload).contains("\"errorCode\":\"INVALID_ROUTE_REQUEST\"");
        assertThat(payload).doesNotContain("retryAfterSeconds");
    }

    @Test
    @DisplayName("WebSocket AI 경로 생성은 깨진 JSON을 INVALID_ROUTE_REQUEST로 구분한다")
    void handleTextMessageMapsMalformedJsonToInvalidRequest() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);

        handler.handle(session, new TextMessage("{not-json"));

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        String payload = messages.getAllValues().get(1).getPayload();
        assertThat(payload).contains("\"code\":\"INVALID_ROUTE_REQUEST\"");
        assertThat(payload).contains("\"errorCode\":\"INVALID_ROUTE_REQUEST\"");
    }

    private String errorPayload(RuntimeException exception) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Principal principal = () -> "42";
        given(session.getPrincipal()).willReturn(principal);
        given(plannerService.plan(eq("42"), any())).willThrow(exception);

        handler.handle(session, new TextMessage("""
                {"lat":37.4812,"lon":126.9527}
                """));

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().get(1).getPayload();
    }

    private AiRoutePlanResponse minimalResponse() {
        return new AiRoutePlanResponse(
                "test-plan",
                "READY",
                "요약",
                "HIGH",
                null,
                null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                70,
                null,
                null,
                java.util.List.of(),
                false
        );
    }

    private static class TestHandler extends AiRouteWebSocketHandler {

        TestHandler(
                ObjectMapper objectMapper,
                AiRoutePlannerService aiRoutePlannerService,
                AiRouteQuotaService aiRouteQuotaService
        ) {
            super(objectMapper, aiRoutePlannerService, aiRouteQuotaService);
        }

        void handle(WebSocketSession session, TextMessage message) throws Exception {
            handleTextMessage(session, message);
        }
    }
}
