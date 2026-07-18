package com.bikeprojectminji.bikeback.party.websocket;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RidePartySocketSessionRegistry {

    private final Set<PartySocketSession> sessions = ConcurrentHashMap.newKeySet();

    public void register(Long partyId, Long userId, WebSocketSession session) {
        sessions.add(new PartySocketSession(partyId, userId, session));
    }

    public void unregister(WebSocketSession session) {
        sessions.removeIf(entry -> entry.session().equals(session));
    }

    public List<PartySocketSession> sessionsForParty(Long partyId) {
        return sessions.stream().filter(entry -> entry.partyId().equals(partyId)).toList();
    }

    public void closeMember(Long partyId, Long userId) {
        closeMatching(
                entry -> entry.partyId().equals(partyId) && entry.userId().equals(userId),
                CloseStatus.POLICY_VIOLATION.withReason("member-left")
        );
    }

    public void closeParty(Long partyId) {
        closeMatching(
                entry -> entry.partyId().equals(partyId),
                CloseStatus.POLICY_VIOLATION.withReason("party-canceled")
        );
    }

    private void closeMatching(
            java.util.function.Predicate<PartySocketSession> predicate,
            CloseStatus closeStatus
    ) {
        for (PartySocketSession entry : sessions.stream().filter(predicate).toList()) {
            close(entry.session(), closeStatus);
            sessions.remove(entry);
        }
    }

    private void close(WebSocketSession session, CloseStatus closeStatus) {
        try {
            if (session.isOpen()) {
                session.close(closeStatus);
            }
        } catch (IOException ignored) {
            // The registry still removes the revoked session even if the transport is already broken.
        }
    }

    public record PartySocketSession(Long partyId, Long userId, WebSocketSession session) {
    }
}
