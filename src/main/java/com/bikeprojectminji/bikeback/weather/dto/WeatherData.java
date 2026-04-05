package com.bikeprojectminji.bikeback.weather.dto;

public record WeatherData(
        Integer temperatureC,
        String sky,
        String precipType
) {
}
