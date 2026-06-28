package com.bikeprojectminji.bikeback.weather.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public record WeatherLocationKey(
        BigDecimal lat,
        BigDecimal lon
) {
    private static final int WEATHER_GRID_SCALE = 2;
    private static final BigDecimal GRID_STEP = new BigDecimal("0.01");

    public static WeatherLocationKey from(BigDecimal lat, BigDecimal lon) {
        return new WeatherLocationKey(
                lat.setScale(WEATHER_GRID_SCALE, RoundingMode.HALF_UP),
                lon.setScale(WEATHER_GRID_SCALE, RoundingMode.HALF_UP)
        );
    }

    public List<WeatherLocationKey> adjacentKeys() {
        List<WeatherLocationKey> keys = new ArrayList<>(8);
        for (int latOffset = -1; latOffset <= 1; latOffset++) {
            for (int lonOffset = -1; lonOffset <= 1; lonOffset++) {
                if (latOffset == 0 && lonOffset == 0) {
                    continue;
                }
                keys.add(new WeatherLocationKey(
                        lat.add(GRID_STEP.multiply(BigDecimal.valueOf(latOffset))).setScale(WEATHER_GRID_SCALE, RoundingMode.HALF_UP),
                        lon.add(GRID_STEP.multiply(BigDecimal.valueOf(lonOffset))).setScale(WEATHER_GRID_SCALE, RoundingMode.HALF_UP)
                ));
            }
        }
        return List.copyOf(keys);
    }
}
