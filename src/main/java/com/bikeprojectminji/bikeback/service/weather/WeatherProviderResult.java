package com.bikeprojectminji.bikeback.service.weather;

public record WeatherProviderResult(
        boolean success,
        WeatherSnapshot snapshot
) {
    public static WeatherProviderResult success(WeatherSnapshot snapshot) {
        return new WeatherProviderResult(true, snapshot);
    }

    public static WeatherProviderResult failure() {
        return new WeatherProviderResult(false, null);
    }
}
