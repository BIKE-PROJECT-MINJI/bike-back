package com.bikeprojectminji.bikeback.weather.dto;

import java.time.OffsetDateTime;

public record CurrentWeatherResponse(
        WeatherData weather,
        WindData wind,
        boolean stale,
        boolean forecastFallbackUsed,
        String freshnessStatus,
        String staleReason,
        OffsetDateTime observedAt,
        long cacheAgeSec
) {
    public CurrentWeatherResponse(WeatherData weather, WindData wind, boolean stale, boolean forecastFallbackUsed) {
        this(
                weather,
                wind,
                stale,
                forecastFallbackUsed,
                stale ? "STALE_LAST_SUCCESS" : "FRESH_PROVIDER",
                stale ? "LAST_SUCCESS_CACHE" : null,
                null,
                0L
        );
    }
}
