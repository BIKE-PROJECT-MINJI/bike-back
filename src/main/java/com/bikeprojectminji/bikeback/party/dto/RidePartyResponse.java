package com.bikeprojectminji.bikeback.party.dto;

import java.time.OffsetDateTime;

public record RidePartyResponse(
        Long id,
        Long courseId,
        Long hostUserId,
        String title,
        OffsetDateTime scheduledStartAt,
        Integer capacity,
        Integer joinedCount,
        String status,
        boolean currentUserMember,
        boolean currentUserHost
) {
}
