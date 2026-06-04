package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import java.util.Optional;

public record AiRouteConditionContext(
        Optional<CurrentWeatherResponse> weather,
        String constructionSummary,
        String roadSurfaceSummary
) {
}
