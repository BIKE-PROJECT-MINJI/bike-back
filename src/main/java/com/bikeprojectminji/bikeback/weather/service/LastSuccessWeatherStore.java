package com.bikeprojectminji.bikeback.weather.service;

import java.util.Optional;

public interface LastSuccessWeatherStore {

    Optional<WeatherSnapshot> find(WeatherLocationKey locationKey);

    void save(WeatherLocationKey locationKey, WeatherSnapshot snapshot);
}
