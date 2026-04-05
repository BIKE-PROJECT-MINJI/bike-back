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
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenMeteoWeatherProvider implements WeatherProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class);

    private final RestClient restClient;
    private final Clock clock;

    public OpenMeteoWeatherProvider(RestClient.Builder restClientBuilder, Clock clock) {
        this.restClient = restClientBuilder.baseUrl("https://api.open-meteo.com").build();
        this.clock = clock;
    }

    @Override
    public WeatherProviderResult getCurrent(WeatherLocationKey locationKey) {
        try {
            OpenMeteoForecastResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/forecast")
                            .queryParam("latitude", locationKey.lat())
                            .queryParam("longitude", locationKey.lon())
                            .queryParam("current", "temperature_2m,weather_code,wind_speed_10m,wind_direction_10m")
                            .queryParam("hourly", "temperature_2m,weather_code,wind_speed_10m,wind_direction_10m")
                            .queryParam("wind_speed_unit", "kmh")
                            .queryParam("timezone", "GMT")
                            .build())
                    .retrieve()
                    .body(OpenMeteoForecastResponse.class);

            if (response == null) {
                return WeatherProviderResult.failure();
            }

            WeatherSnapshot currentSnapshot = mapCurrent(response.current());
            if (currentSnapshot != null) {
                return WeatherProviderResult.success(currentSnapshot);
            }

            WeatherSnapshot fallbackSnapshot = mapFallback(response.hourly());
            return fallbackSnapshot != null ? WeatherProviderResult.success(fallbackSnapshot) : WeatherProviderResult.failure();
        } catch (RuntimeException exception) {
            log.warn("weather provider failure lat={} lon={}", locationKey.lat(), locationKey.lon(), exception);
            return WeatherProviderResult.failure();
        }
    }

    private WeatherSnapshot mapCurrent(OpenMeteoForecastResponse.Current current) {
        if (current == null || current.temperatureC() == null || current.windSpeedKmh() == null || current.windDirectionDeg() == null) {
            return null;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return new WeatherSnapshot(
                new WeatherData((int) Math.round(current.temperatureC()), mapSky(current.weatherCode()), mapPrecipType(current.weatherCode())),
                new WindData((int) Math.round(current.windSpeedKmh()), toDirectionText(current.windDirectionDeg()), current.windDirectionDeg()),
                false,
                now,
                now
        );
    }

    private WeatherSnapshot mapFallback(OpenMeteoForecastResponse.Hourly hourly) {
        if (hourly == null || hourly.time() == null || hourly.time().isEmpty()) {
            return null;
        }

        OffsetDateTime target = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        int index = hourly.time().indexOf(target.toLocalDateTime().toString());
        if (index < 0) {
            index = 0;
        }

        if (!hasHourlyValue(hourly.temperatureC(), index) || !hasHourlyValue(hourly.windSpeedKmh(), index) || !hasHourlyValue(hourly.windDirectionDeg(), index)) {
            return null;
        }

        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Integer weatherCode = hasHourlyValue(hourly.weatherCode(), index) ? hourly.weatherCode().get(index) : null;
        return new WeatherSnapshot(
                new WeatherData((int) Math.round(hourly.temperatureC().get(index)), mapSky(weatherCode), mapPrecipType(weatherCode)),
                new WindData((int) Math.round(hourly.windSpeedKmh().get(index)), toDirectionText(hourly.windDirectionDeg().get(index)), hourly.windDirectionDeg().get(index)),
                true,
                target,
                now
        );
    }

    private boolean hasHourlyValue(List<?> values, int index) {
        return values != null && index >= 0 && index < values.size() && values.get(index) != null;
    }

    private String mapSky(Integer weatherCode) {
        if (weatherCode == null) {
            return "unknown";
        }
        if (weatherCode == 0) {
            return "clear";
        }
        if (weatherCode == 1 || weatherCode == 2 || weatherCode == 3 || weatherCode == 45 || weatherCode == 48) {
            return "cloudy";
        }
        if ((weatherCode >= 51 && weatherCode <= 67) || (weatherCode >= 80 && weatherCode <= 82) || (weatherCode >= 95 && weatherCode <= 99)) {
            return "rain";
        }
        if ((weatherCode >= 71 && weatherCode <= 77) || weatherCode == 85 || weatherCode == 86) {
            return "snow";
        }
        return "unknown";
    }

    private String mapPrecipType(Integer weatherCode) {
        if (weatherCode == null) {
            return "none";
        }
        if ((weatherCode >= 51 && weatherCode <= 67) || (weatherCode >= 80 && weatherCode <= 82) || weatherCode == 95) {
            return "rain";
        }
        if ((weatherCode >= 71 && weatherCode <= 77) || weatherCode == 85 || weatherCode == 86) {
            return "snow";
        }
        if (weatherCode >= 96 && weatherCode <= 99) {
            return "mixed";
        }
        return "none";
    }

    private String toDirectionText(Integer degree) {
        if (degree == null) {
            return "알 수 없음";
        }
        String[] directions = {"북", "북동", "동", "남동", "남", "남서", "서", "북서"};
        int index = (int) Math.round(((degree % 360) / 45.0)) % 8;
        return directions[index];
    }
}
