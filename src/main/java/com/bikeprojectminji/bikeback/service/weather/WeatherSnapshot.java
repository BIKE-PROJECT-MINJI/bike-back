package com.bikeprojectminji.bikeback.service.weather;

import com.bikeprojectminji.bikeback.dto.weather.WeatherData;
import com.bikeprojectminji.bikeback.dto.weather.WindData;
import java.time.OffsetDateTime;

public record WeatherSnapshot(
        WeatherData weather,
        WindData wind,
        boolean forecastFallbackUsed,
        OffsetDateTime observedAt,
        OffsetDateTime lastSucceededAt
) {
}
