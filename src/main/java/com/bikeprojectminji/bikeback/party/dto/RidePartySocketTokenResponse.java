package com.bikeprojectminji.bikeback.party.dto;

import java.time.OffsetDateTime;

public record RidePartySocketTokenResponse(
        String socketToken,
        OffsetDateTime expiresAt
) {
}
