package com.bikeprojectminji.bikeback.event.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record CreateClientEventRequest(
        String eventName,
        Integer eventVersion,
        String sessionId,
        OffsetDateTime occurredAtClient,
        String screenName,
        Long courseId,
        Long rideRecordId,
        String appVersion,
        String osName,
        String deviceType,
        String locationPermissionState,
        JsonNode properties
) {
}
