package com.bikeprojectminji.bikeback.party.service;

import java.time.OffsetDateTime;

public record RidePartySocketTokenPayload(
        Long partyId,
        Long userId,
        OffsetDateTime expiresAt
) {
}
