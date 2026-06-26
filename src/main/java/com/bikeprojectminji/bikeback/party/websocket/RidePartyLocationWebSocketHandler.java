package com.bikeprojectminji.bikeback.party.websocket;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.validation.CoordinateValidator;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenPayload;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RidePartyLocationWebSocketHandler extends TextWebSocketHandler {

    private static final String PARTY_ID_ATTRIBUTE = "partyId";
    private static final String USER_ID_ATTRIBUTE = "userId";

    private final ObjectMapper objectMapper;
    private final RidePartySocketTokenService socketTokenService;
    private final Map<Long, Set<WebSocketSession>> sessionsByPartyId = new ConcurrentHashMap<>();

    public RidePartyLocationWebSocketHandler(
            ObjectMapper objectMapper,
            RidePartySocketTokenService socketTokenService
    ) {
        this.objectMapper = objectMapper;
        this.socketTokenService = socketTokenService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long partyId = extractPartyId(session.getUri());
        Optional<RidePartySocketTokenPayload> token = socketTokenService.consume(bearerToken(session.getHandshakeHeaders()), partyId);
        if (token.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid party socket token"));
            return;
        }
        session.getAttributes().put(PARTY_ID_ATTRIBUTE, partyId);
        session.getAttributes().put(USER_ID_ATTRIBUTE, token.get().userId());
        sessionsByPartyId.computeIfAbsent(partyId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        session.sendMessage(toMessage(Map.of("type", "connected", "partyId", partyId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long partyId = attribute(session, PARTY_ID_ATTRIBUTE);
        Long userId = attribute(session, USER_ID_ATTRIBUTE);
        if (partyId == null || userId == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            RidePartyLocationMessage location = objectMapper.readValue(message.getPayload(), RidePartyLocationMessage.class);
            CoordinateValidator.validateLatLon("latitude", location.latitude(), "longitude", location.longitude());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("partyId", partyId);
            data.put("userId", userId);
            data.put("latitude", location.latitude());
            data.put("longitude", location.longitude());
            data.put("accuracyM", location.accuracyM());
            data.put("speedMps", location.speedMps());
            data.put("bearingDeg", location.bearingDeg());
            data.put("capturedAt", location.capturedAt() == null ? OffsetDateTime.now() : location.capturedAt());
            broadcast(partyId, Map.of(
                    "type", "location",
                    "data", data
            ));
        } catch (BadRequestException exception) {
            session.sendMessage(toMessage(Map.of("type", "error", "code", "BAD_LOCATION", "message", exception.getMessage())));
        } catch (RuntimeException exception) {
            session.sendMessage(toMessage(Map.of("type", "error", "message", "파티 위치를 처리하지 못했습니다.")));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long partyId = attribute(session, PARTY_ID_ATTRIBUTE);
        if (partyId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByPartyId.get(partyId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByPartyId.remove(partyId);
            }
        }
    }

    private void broadcast(Long partyId, Object payload) throws Exception {
        TextMessage message = toMessage(payload);
        Set<WebSocketSession> sessions = sessionsByPartyId.getOrDefault(partyId, Set.of());
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        }
    }

    private TextMessage toMessage(Object payload) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(payload));
    }

    private String bearerToken(HttpHeaders headers) {
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return "";
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    private Long extractPartyId(URI uri) {
        if (uri == null || uri.getPath() == null) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        String[] parts = uri.getPath().split("/");
        if (parts.length < 5) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        try {
            return Long.parseLong(parts[4]);
        } catch (NumberFormatException exception) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
    }

    @SuppressWarnings("unchecked")
    private Long attribute(WebSocketSession session, String attributeName) {
        Object value = session.getAttributes().get(attributeName);
        return value instanceof Long longValue ? longValue : null;
    }

}
