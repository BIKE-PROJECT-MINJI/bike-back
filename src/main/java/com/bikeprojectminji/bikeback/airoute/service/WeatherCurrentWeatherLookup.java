package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.service.WeatherService;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WeatherCurrentWeatherLookup implements CurrentWeatherLookup {

    private final WeatherService weatherService;

    public WeatherCurrentWeatherLookup(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public Optional<CurrentWeatherResponse> find(BigDecimal lat, BigDecimal lon) {
        try {
            CurrentWeatherResponse current = weatherService.getCurrent(lat, lon);
            if (current.weather() == null || current.wind() == null) {
                return Optional.empty();
            }
            return Optional.of(current);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
