package com.bikeprojectminji.bikeback.service.weather;

import com.bikeprojectminji.bikeback.dto.weather.CurrentWeatherResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final Duration LAST_SUCCESS_TTL = Duration.ofMinutes(60);

    private final WeatherProviderPort weatherProviderPort;
    private final LastSuccessWeatherStore lastSuccessWeatherStore;
    private final Clock clock;

    public WeatherService(
            WeatherProviderPort weatherProviderPort,
            LastSuccessWeatherStore lastSuccessWeatherStore,
            Clock clock
    ) {
        this.weatherProviderPort = weatherProviderPort;
        this.lastSuccessWeatherStore = lastSuccessWeatherStore;
        this.clock = clock;
    }

    public CurrentWeatherResponse getCurrent(BigDecimal lat, BigDecimal lon) {
        WeatherLocationKey locationKey = WeatherLocationKey.from(lat, lon);
        WeatherProviderResult providerResult = weatherProviderPort.getCurrent(locationKey);

        if (providerResult.success() && providerResult.snapshot() != null) {
            lastSuccessWeatherStore.save(locationKey, providerResult.snapshot());
            return toResponse(providerResult.snapshot(), false);
        }

        Optional<WeatherSnapshot> fallback = lastSuccessWeatherStore.find(locationKey)
                .filter(this::isWithinLastSuccessTtl);

        if (fallback.isPresent()) {
            log.info("weather fallback used locationKey={}", locationKey);
            return toResponse(fallback.get(), true);
        }

        log.info("weather unavailable locationKey={}", locationKey);
        return null;
    }

    private boolean isWithinLastSuccessTtl(WeatherSnapshot snapshot) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return Duration.between(snapshot.lastSucceededAt(), now).compareTo(LAST_SUCCESS_TTL) <= 0;
    }

    private CurrentWeatherResponse toResponse(WeatherSnapshot snapshot, boolean stale) {
        return new CurrentWeatherResponse(
                snapshot.weather(),
                snapshot.wind(),
                stale,
                snapshot.forecastFallbackUsed()
        );
    }
}
