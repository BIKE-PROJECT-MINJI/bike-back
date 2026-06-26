package com.bikeprojectminji.bikeback.party.dto;

import java.time.OffsetDateTime;

public record CreateRidePartyRequest(
        Long courseId,
        String title,
        OffsetDateTime scheduledStartAt,
        Integer capacity
) {
}
