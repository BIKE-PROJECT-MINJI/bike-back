package com.bikeprojectminji.bikeback.event.dto;

import java.util.List;

public record CreateClientEventBatchRequest(
        List<CreateClientEventRequest> events
) {
}
