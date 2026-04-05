package com.bikeprojectminji.bikeback.weather.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoForecastResponse(
        Current current,
        Hourly hourly
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(
            @JsonProperty("temperature_2m") Double temperatureC,
            @JsonProperty("weather_code") Integer weatherCode,
            @JsonProperty("wind_speed_10m") Double windSpeedKmh,
            @JsonProperty("wind_direction_10m") Integer windDirectionDeg
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hourly(
            List<String> time,
            @JsonProperty("temperature_2m") List<Double> temperatureC,
            @JsonProperty("weather_code") List<Integer> weatherCode,
            @JsonProperty("wind_speed_10m") List<Double> windSpeedKmh,
            @JsonProperty("wind_direction_10m") List<Integer> windDirectionDeg
    ) {
    }
}
