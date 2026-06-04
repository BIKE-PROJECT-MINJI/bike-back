package com.bikeprojectminji.bikeback.condition.service;

import java.math.BigDecimal;

public record RouteConditionRequest(
        BigDecimal lat,
        BigDecimal lon
) {
}
