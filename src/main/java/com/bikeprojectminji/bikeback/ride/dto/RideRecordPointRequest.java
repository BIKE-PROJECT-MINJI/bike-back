package com.bikeprojectminji.bikeback.ride.dto;

import java.math.BigDecimal;

public record RideRecordPointRequest(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
