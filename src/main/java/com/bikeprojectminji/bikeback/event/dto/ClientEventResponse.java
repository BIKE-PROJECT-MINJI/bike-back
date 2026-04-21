package com.bikeprojectminji.bikeback.event.dto;

import java.time.OffsetDateTime;

public record ClientEventResponse(
        Long eventId,
        OffsetDateTime receivedAt
) {
}
