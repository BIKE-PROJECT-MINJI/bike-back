package com.bikeprojectminji.bikeback.weather.service;

public interface WeatherProviderPort {

    WeatherProviderResult getCurrent(WeatherLocationKey locationKey);
}
