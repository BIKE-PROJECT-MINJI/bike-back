package com.bikeprojectminji.bikeback.party.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bikeprojectminji.bikeback.party.service.RidePartyLocationService;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenPayload;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class RidePartyLocationWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RidePartySocketTokenService socketTokenService = mock(RidePartySocketTokenService.class);
    private final RidePartyLocationService locationService = mock(RidePartyLocationService.class);
    private final RidePartyLocationWebSocketHandler handler = new RidePartyLocationWebSocketHandler(
            objectMapper,
            socketTokenService,
            locationService
    );

    @Test
    @DisplayName("파티 위치 WebSocket은 socket token을 소비하고 같은 파티 세션에 위치를 브로드캐스트한다")
    void broadcastsLocationToPartySessionsAfterTokenAuthentication() throws Exception {
        WebSocketSession sender = session("token-1");
        WebSocketSession receiver = session("token-2");
        when(socketTokenService.consume("token-1", 20L))
                .thenReturn(Optional.of(new RidePartySocketTokenPayload(20L, 2L, OffsetDateTime.now().plusMinutes(1))));
        when(socketTokenService.consume("token-2", 20L))
                .thenReturn(Optional.of(new RidePartySocketTokenPayload(20L, 3L, OffsetDateTime.now().plusMinutes(1))));

        handler.afterConnectionEstablished(sender);
        handler.afterConnectionEstablished(receiver);
        handler.handleTextMessage(sender, new TextMessage("""
                {
                  "latitude": 37.5001,
                  "longitude": 127.0002,
                  "accuracyM": 8.5,
                  "capturedAt": "2026-06-26T00:00:00Z"
                }
                """));

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(receiver, atLeastOnce()).sendMessage(messages.capture());
        assertThat(messages.getAllValues())
                .extracting(TextMessage::getPayload)
                .anySatisfy(payload -> {
                    assertThat(payload).contains("\"type\":\"location\"");
                    assertThat(payload).contains("\"partyId\":20");
                    assertThat(payload).contains("\"userId\":2");
                    assertThat(payload).contains("\"latitude\":37.5001");
        });
        verify(socketTokenService).consume("token-1", 20L);
        verify(socketTokenService).consume("token-2", 20L);
        verify(locationService).saveLocation(eq(20L), eq(2L), org.mockito.ArgumentMatchers.any());
    }

    private WebSocketSession session(String token) {
        WebSocketSession session = mock(WebSocketSession.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/v1/parties/20/locations"));
        when(session.getHandshakeHeaders()).thenReturn(headers);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
