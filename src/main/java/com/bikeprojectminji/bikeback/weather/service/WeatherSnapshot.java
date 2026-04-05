package com.bikeprojectminji.bikeback.weather.service;

import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import java.time.OffsetDateTime;

public record WeatherSnapshot(
        WeatherData weather,
        WindData wind,
        boolean forecastFallbackUsed,
        OffsetDateTime observedAt,
        OffsetDateTime lastSucceededAt
) {
}
