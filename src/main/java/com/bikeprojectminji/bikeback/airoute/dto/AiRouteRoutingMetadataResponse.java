package com.bikeprojectminji.bikeback.airoute.dto;

import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePlan;

public record AiRouteRoutingMetadataResponse(
        String routingStatus,
        String provider,
        boolean fallbackUsed,
        String fallbackReason,
        String qualityStatus,
        String qualityMessage
) {

    public static AiRouteRoutingMetadataResponse from(BicycleRoutePlan routePlan) {
        if (routePlan == null) {
            return null;
        }
        return new AiRouteRoutingMetadataResponse(
                routePlan.status(),
                routePlan.provider(),
                routePlan.fallbackUsed(),
                routePlan.fallbackReason(),
                routePlan.qualityStatus(),
                routePlan.qualityMessage()
        );
    }
}
