package com.bikeprojectminji.bikeback.party.websocket;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.party.service.RidePartyLocationService;
import com.bikeprojectminji.bikeback.party.service.RidePartyLocationAccessService;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenPayload;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
    private final RidePartyLocationService locationService;
    private final RidePartyLocationAccessService locationAccessService;
    private final RidePartySocketSessionRegistry sessionRegistry;
    private final RidePartyDistributedStateService distributedStateService;

    public RidePartyLocationWebSocketHandler(
            ObjectMapper objectMapper,
            RidePartySocketTokenService socketTokenService,
            RidePartyLocationService locationService,
            RidePartyLocationAccessService locationAccessService,
            RidePartySocketSessionRegistry sessionRegistry,
            RidePartyDistributedStateService distributedStateService
    ) {
        this.objectMapper = objectMapper;
        this.socketTokenService = socketTokenService;
        this.locationService = locationService;
        this.locationAccessService = locationAccessService;
        this.sessionRegistry = sessionRegistry;
        this.distributedStateService = distributedStateService;
    }

    RidePartyLocationWebSocketHandler(
            ObjectMapper objectMapper,
            RidePartySocketTokenService socketTokenService,
            RidePartyLocationService locationService,
            RidePartyLocationAccessService locationAccessService,
            RidePartySocketSessionRegistry sessionRegistry
    ) {
        this(objectMapper, socketTokenService, locationService, locationAccessService, sessionRegistry, null);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long partyId = extractPartyId(session.getUri());
        Optional<RidePartySocketTokenPayload> token = socketTokenService.consume(socketToken(session.getUri()), partyId);
        if (token.isEmpty()) {
            sessionRegistry.close(session, CloseStatus.POLICY_VIOLATION.withReason("invalid party socket token"));
            return;
        }
        if (!locationAccessService.canShare(partyId, token.get().userId())) {
            sessionRegistry.close(session, CloseStatus.POLICY_VIOLATION.withReason("party location access denied"));
            return;
        }
        session.getAttributes().put(PARTY_ID_ATTRIBUTE, partyId);
        session.getAttributes().put(USER_ID_ATTRIBUTE, token.get().userId());
        boolean registered = distributedStateService == null
                ? registerLocally(partyId, token.get().userId(), session)
                : distributedStateService.registerIfSubscriberHealthy(partyId, token.get().userId(), session);
        if (!registered) {
            sessionRegistry.close(session, CloseStatus.POLICY_VIOLATION.withReason("distributed subscriber unavailable"));
            return;
        }
        sessionRegistry.sessionsForParty(partyId).stream().filter(entry -> entry.session().equals(session)).findFirst()
                .ifPresent(entry -> sessionRegistry.send(entry, uncheckedMessage(Map.of("type", "connected", "partyId", partyId))));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Long partyId = attribute(session, PARTY_ID_ATTRIBUTE);
        Long userId = attribute(session, USER_ID_ATTRIBUTE);
        if (partyId == null || userId == null || !locationAccessService.canShare(partyId, userId)) {
            sessionRegistry.close(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            RidePartyLocationMessage location = objectMapper.readValue(message.getPayload(), RidePartyLocationMessage.class);
            location.validate();
            OffsetDateTime capturedAt = location.capturedAt() == null ? OffsetDateTime.now() : location.capturedAt();
            new RidePartyLocationMessage(location.latitude(), location.longitude(), location.accuracyM(), location.speedMps(), location.bearingDeg(), capturedAt).validateCapturedAt(OffsetDateTime.now());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("partyId", partyId);
            data.put("userId", userId);
            data.put("latitude", location.latitude());
            data.put("longitude", location.longitude());
            data.put("accuracyM", location.accuracyM());
            data.put("speedMps", location.speedMps());
            data.put("bearingDeg", location.bearingDeg());
            data.put("capturedAt", capturedAt);
            if (distributedStateService != null) {
                distributedStateService.publishLocation(partyId, userId, data);
            }
            locationService.saveLocation(partyId, userId, location);
            broadcast(partyId, Map.of("type", "location", "data", data));
        } catch (BadRequestException exception) {
            sessionRegistry.send(session, toMessage(Map.of("type", "error", "code", "BAD_LOCATION", "message", exception.getMessage())));
        } catch (RidePartyDistributedPublishException exception) {
            sessionRegistry.send(session, toMessage(Map.of("type", "error", "code", "DISTRIBUTED_DELIVERY_UNAVAILABLE")));
        } catch (RuntimeException exception) {
            sessionRegistry.send(session, toMessage(Map.of("type", "error", "message", "파티 위치를 처리하지 못했습니다.")));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.unregister(session);
    }

    private boolean registerLocally(Long partyId, Long userId, WebSocketSession session) {
        sessionRegistry.register(partyId, userId, session);
        return true;
    }
    private void broadcast(Long partyId, Object payload) throws Exception {
        TextMessage message = toMessage(payload);
        for (RidePartySocketSessionRegistry.PartySocketSession entry : sessionRegistry.sessionsForParty(partyId)) {
            try {
                WebSocketSession session = entry.session();
                if (!locationAccessService.canShare(entry.partyId(), entry.userId())) {
                    sessionRegistry.close(session, CloseStatus.POLICY_VIOLATION);
                } else {
                    sessionRegistry.send(entry, message);
                }
            } catch (RuntimeException exception) {
                sessionRegistry.close(entry.session(), CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    private TextMessage toMessage(Object payload) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(payload));
    }

    private TextMessage uncheckedMessage(Object payload) {
        try { return toMessage(payload); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String socketToken(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return "";
        }
        for (String pair : uri.getRawQuery().split("&")) {
            int separator = pair.indexOf('=');
            String name = separator >= 0 ? pair.substring(0, separator) : pair;
            if (!"socketToken".equals(urlDecode(name))) {
                continue;
            }
            String value = separator >= 0 ? pair.substring(separator + 1) : "";
            return urlDecode(value).trim();
        }
        return "";
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
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
