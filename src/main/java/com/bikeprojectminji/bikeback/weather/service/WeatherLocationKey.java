package com.bikeprojectminji.bikeback.weather.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record WeatherLocationKey(
        BigDecimal lat,
        BigDecimal lon
) {
    public static WeatherLocationKey from(BigDecimal lat, BigDecimal lon) {
        return new WeatherLocationKey(
                lat.setScale(4, RoundingMode.HALF_UP),
                lon.setScale(4, RoundingMode.HALF_UP)
        );
    }
}
