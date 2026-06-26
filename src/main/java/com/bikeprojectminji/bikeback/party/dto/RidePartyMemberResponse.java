package com.bikeprojectminji.bikeback.party.dto;

import java.time.OffsetDateTime;

public record RidePartyMemberResponse(
        Long userId,
        String role,
        String status,
        OffsetDateTime joinedAt
) {
}
