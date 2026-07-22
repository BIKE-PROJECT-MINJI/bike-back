package com.bikeprojectminji.bikeback.party.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** Versioned payload exchanged by party WebSocket nodes over Redis Pub/Sub. */
public record RidePartyDistributedEvent(
        int version,
        String eventId,
        String sourceNodeId,
        EventType eventType,
        Long partyId,
        Long userId,
        JsonNode payload,
        OffsetDateTime emittedAt
) {

    public static final int VERSION = 1;

    public enum EventType {
        LOCATION,
        MEMBER_REVOKED,
        PARTY_CANCELED
    }
}
