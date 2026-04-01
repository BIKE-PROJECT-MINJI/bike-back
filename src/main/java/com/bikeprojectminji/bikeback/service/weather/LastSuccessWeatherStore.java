package com.bikeprojectminji.bikeback.service.weather;

import java.util.Optional;

public interface LastSuccessWeatherStore {

    Optional<WeatherSnapshot> find(WeatherLocationKey locationKey);

    void save(WeatherLocationKey locationKey, WeatherSnapshot snapshot);
}
