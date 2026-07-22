package com.bikeprojectminji.bikeback.party.websocket;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RidePartySocketSessionRegistry {

    private final Set<PartySocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<WebSocketSession, Object> writeLocks = new ConcurrentHashMap<>();
    private final AtomicLong generations = new AtomicLong();
    private final Set<WebSocketSession> closeStarted = ConcurrentHashMap.newKeySet();

    public void register(Long partyId, Long userId, WebSocketSession session) {
        sessions.add(new PartySocketSession(partyId, userId, session, OffsetDateTime.now(), generations.incrementAndGet()));
    }

    public void unregister(WebSocketSession session) {
        sessions.removeIf(entry -> entry.session().equals(session));
        // Keep the lock for the lifetime of this session object: removing it while an old
        // callback is in flight could split old/new writes across two locks.
    }

    public List<PartySocketSession> sessionsForParty(Long partyId) {
        return sessions.stream().filter(entry -> partyId == null || entry.partyId().equals(partyId)).toList();
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

    public void closeAll() {
        closeMatching(entry -> true, CloseStatus.POLICY_VIOLATION.withReason("distributed-bus-unavailable"));
    }

    public void closeMemberBefore(Long partyId, Long userId, OffsetDateTime emittedAt) {
        closeMatching(entry -> entry.partyId().equals(partyId) && entry.userId().equals(userId)
                        && !entry.connectedAt().isAfter(emittedAt),
                CloseStatus.POLICY_VIOLATION.withReason("member-left"));
    }

    public void closePartyBefore(Long partyId, OffsetDateTime emittedAt) {
        closeMatching(entry -> entry.partyId().equals(partyId) && !entry.connectedAt().isAfter(emittedAt),
                CloseStatus.POLICY_VIOLATION.withReason("party-canceled"));
    }

    public boolean send(PartySocketSession entry, org.springframework.web.socket.TextMessage message) {
        return send(entry.session(), message);
    }

    public boolean send(WebSocketSession session, org.springframework.web.socket.TextMessage message) {
        Object lock = writeLocks.computeIfAbsent(session, ignored -> new Object());
        synchronized (lock) {
            if (closeStarted.contains(session) || !session.isOpen()) {
                unregister(session);
                return false;
            }
            try {
                session.sendMessage(message);
                return true;
            } catch (IOException | RuntimeException exception) {
                closeLocked(session, CloseStatus.SERVER_ERROR);
                return false;
            }
        }
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

    public void close(WebSocketSession session, CloseStatus closeStatus) {
        Object lock = writeLocks.computeIfAbsent(session, ignored -> new Object());
        synchronized (lock) {
            closeLocked(session, closeStatus);
        }
    }

    private void closeLocked(WebSocketSession session, CloseStatus closeStatus) {
        if (!closeStarted.add(session)) {
            unregister(session);
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(closeStatus);
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken transport cannot prevent cleanup of this or another party session.
        } finally {
            unregister(session);
        }
    }

    public record PartySocketSession(Long partyId, Long userId, WebSocketSession session,
                                     OffsetDateTime connectedAt, long generation) {
    }
}
