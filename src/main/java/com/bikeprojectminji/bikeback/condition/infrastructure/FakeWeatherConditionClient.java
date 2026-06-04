package com.bikeprojectminji.bikeback.condition.infrastructure;

import com.bikeprojectminji.bikeback.condition.service.RouteConditionEvidence;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionRequest;
import com.bikeprojectminji.bikeback.condition.service.WeatherConditionClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class FakeWeatherConditionClient implements WeatherConditionClient {

    @Override
    public String source() {
        return "weather";
    }

    @Override
    public String label() {
        return "날씨";
    }

    @Override
    public RouteConditionEvidence lookup(RouteConditionRequest request) {
        return RouteConditionEvidence.verified(source(), label(), "맑음, 북서풍 12km/h");
    }
}
