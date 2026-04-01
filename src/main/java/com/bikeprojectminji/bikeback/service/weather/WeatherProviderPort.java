package com.bikeprojectminji.bikeback.service.weather;

public interface WeatherProviderPort {

    WeatherProviderResult getCurrent(WeatherLocationKey locationKey);
}
