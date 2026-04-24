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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Component
public class OpenMeteoWeatherProvider implements WeatherProviderPort {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoWeatherProvider.class);
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 300;
    private static final int DEFAULT_READ_TIMEOUT_MS = 1000;

    private final RestClient restClient;
    private final Clock clock;

    @Autowired
    public OpenMeteoWeatherProvider(
            RestClient.Builder restClientBuilder,
            Clock clock,
            @Value("${weather.provider.open-meteo.connect-timeout-ms:" + DEFAULT_CONNECT_TIMEOUT_MS + "}") int connectTimeoutMs,
            @Value("${weather.provider.open-meteo.read-timeout-ms:" + DEFAULT_READ_TIMEOUT_MS + "}") int readTimeoutMs
    ) {
        this(
                restClientBuilder
                        .baseUrl("https://api.open-meteo.com")
                        .requestFactory(WeatherRequestFactoryFactory.create(connectTimeoutMs, readTimeoutMs))
                        .build(),
                clock
        );
    }

    public OpenMeteoWeatherProvider(RestClient.Builder restClientBuilder, Clock clock) {
        this(restClientBuilder, clock, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    OpenMeteoWeatherProvider(RestClient restClient, Clock clock) {
        this.restClient = restClient;
        this.clock = clock;
    }

    @Override
    public WeatherProviderResult getCurrent(WeatherLocationKey locationKey) {
        long startedAtNanos = System.nanoTime();
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
                log.info(
                        "weather_provider_result request_id={} lat={} lon={} outcome=failure reason=null_response duration_ms={}",
                        com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(),
                        locationKey.lat(),
                        locationKey.lon(),
                        toDurationMs(startedAtNanos)
                );
                return WeatherProviderResult.failure();
            }

            WeatherSnapshot currentSnapshot = mapCurrent(response.current());
            if (currentSnapshot != null) {
                log.info(
                        "weather_provider_result request_id={} lat={} lon={} outcome=success source=current duration_ms={} forecast_fallback_used={}",
                        com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(),
                        locationKey.lat(),
                        locationKey.lon(),
                        toDurationMs(startedAtNanos),
                        currentSnapshot.forecastFallbackUsed()
                );
                return WeatherProviderResult.success(currentSnapshot);
            }

            WeatherSnapshot fallbackSnapshot = mapFallback(response.hourly());
            if (fallbackSnapshot != null) {
                log.info(
                        "weather_provider_result request_id={} lat={} lon={} outcome=success source=hourly_fallback duration_ms={} forecast_fallback_used={}",
                        com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(),
                        locationKey.lat(),
                        locationKey.lon(),
                        toDurationMs(startedAtNanos),
                        fallbackSnapshot.forecastFallbackUsed()
                );
                return WeatherProviderResult.success(fallbackSnapshot);
            }

            log.info(
                    "weather_provider_result request_id={} lat={} lon={} outcome=failure reason=no_usable_payload duration_ms={}",
                    com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(),
                    locationKey.lat(),
                    locationKey.lon(),
                    toDurationMs(startedAtNanos)
            );
            return WeatherProviderResult.failure();
        } catch (RuntimeException exception) {
            log.warn(
                    "weather provider failure request_id={} lat={} lon={} duration_ms={}",
                    com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(),
                    locationKey.lat(),
                    locationKey.lon(),
                    toDurationMs(startedAtNanos),
                    exception
            );
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

    private long toDurationMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
