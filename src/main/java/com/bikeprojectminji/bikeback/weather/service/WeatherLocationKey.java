package com.bikeprojectminji.bikeback.weather.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record WeatherLocationKey(
        BigDecimal lat,
        BigDecimal lon
) {
    private static final int WEATHER_GRID_SCALE = 2;

    public static WeatherLocationKey from(BigDecimal lat, BigDecimal lon) {
        return new WeatherLocationKey(
                lat.setScale(WEATHER_GRID_SCALE, RoundingMode.HALF_UP),
                lon.setScale(WEATHER_GRID_SCALE, RoundingMode.HALF_UP)
        );
    }
}
