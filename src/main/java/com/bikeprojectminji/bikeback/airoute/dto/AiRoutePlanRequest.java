package com.bikeprojectminji.bikeback.airoute.dto;

import java.math.BigDecimal;

public record AiRoutePlanRequest(
        BigDecimal lat,
        BigDecimal lon,
        BigDecimal destinationLat,
        BigDecimal destinationLon,
        String destinationLabel,
        String rideStyle,
        String elevationPreference,
        String textIntent
) {

    public AiRoutePlanRequest(
            BigDecimal lat,
            BigDecimal lon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String destinationLabel,
            String rideStyle
    ) {
        this(lat, lon, destinationLat, destinationLon, destinationLabel, rideStyle, null, null);
    }
}
