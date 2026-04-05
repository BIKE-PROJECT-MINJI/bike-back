package com.bikeprojectminji.bikeback.weather.dto;

public record CurrentWeatherResponse(
        WeatherData weather,
        WindData wind,
        boolean stale,
        boolean forecastFallbackUsed
) {
}
