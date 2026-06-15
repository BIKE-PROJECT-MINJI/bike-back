package com.bikeprojectminji.bikeback.weather.infrastructure;

import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import com.bikeprojectminji.bikeback.weather.service.WeatherLocationKey;
import com.bikeprojectminji.bikeback.weather.service.WeatherProviderPort;
import com.bikeprojectminji.bikeback.weather.service.WeatherProviderResult;
import com.bikeprojectminji.bikeback.weather.service.WeatherSnapshot;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "weather", name = "provider", havingValue = "fake")
public class FakeWeatherProvider implements WeatherProviderPort {

    private final Clock clock;

    public FakeWeatherProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public WeatherProviderResult getCurrent(WeatherLocationKey locationKey) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        WeatherSnapshot snapshot = new WeatherSnapshot(
                new WeatherData(21, "clear", "none"),
                new WindData(12, "북동", 45),
                false,
                now,
                now
        );
        return WeatherProviderResult.success(snapshot);
    }
}
