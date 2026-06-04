package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import java.math.BigDecimal;
import java.util.Optional;

public interface CurrentWeatherLookup {

    Optional<CurrentWeatherResponse> find(BigDecimal lat, BigDecimal lon);
}
