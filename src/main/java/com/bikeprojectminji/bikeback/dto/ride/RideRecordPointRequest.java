package com.bikeprojectminji.bikeback.dto.ride;

import java.math.BigDecimal;

public record RideRecordPointRequest(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
