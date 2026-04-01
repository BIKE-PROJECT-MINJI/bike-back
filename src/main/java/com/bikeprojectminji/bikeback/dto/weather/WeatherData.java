package com.bikeprojectminji.bikeback.dto.weather;

public record WeatherData(
        Integer temperatureC,
        String sky,
        String precipType
) {
}
