package com.bikeprojectminji.bikeback.dto.weather;

public record CurrentWeatherResponse(
        WeatherData weather,
        WindData wind,
        boolean stale,
        boolean forecastFallbackUsed
) {
}
