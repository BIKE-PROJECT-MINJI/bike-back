package com.bikeprojectminji.bikeback.airoute.session.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.math.BigDecimal;

public record AiRouteGenerationSessionCreateRequest(
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal destinationLat,
        BigDecimal destinationLon,
        String destinationLabel,
        String rideStyle,
        String elevationPreference,
        @JsonAlias("text")
        String textIntent
) {
}
