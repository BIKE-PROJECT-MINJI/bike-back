package com.bikeprojectminji.bikeback.airoute.dto;

public record AiRouteWorkerMetadataResponse(
        String provider,
        boolean fallbackUsed,
        String fallbackReason
) {
}
