package com.bikeprojectminji.bikeback.weather.service;

import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
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
    private static final String WEATHER_UNAVAILABLE_MESSAGE = "현재 날씨 정보를 사용할 수 없습니다.";

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
        // 현재 날씨 조회는 provider 성공을 우선 사용하고,
        // 실패 시에는 last-success 캐시를 60분 범위 안에서만 fallback으로 허용한다.
        WeatherLocationKey locationKey = WeatherLocationKey.from(lat, lon);
        WeatherProviderResult providerResult = weatherProviderPort.getCurrent(locationKey);

        if (providerResult.success() && providerResult.snapshot() != null) {
            lastSuccessWeatherStore.save(locationKey, providerResult.snapshot());
            return toResponse(providerResult.snapshot(), false);
        }

        Optional<WeatherSnapshot> fallback = lastSuccessWeatherStore.find(locationKey)
                .filter(this::isWithinLastSuccessTtl);

        if (fallback.isPresent()) {
            log.info("weather_fallback_used request_id={} location_key={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), locationKey);
            return toResponse(fallback.get(), true);
        }

        log.info("weather_unavailable request_id={} location_key={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), locationKey);
        throw new NotFoundException(WEATHER_UNAVAILABLE_MESSAGE);
    }

    private boolean isWithinLastSuccessTtl(WeatherSnapshot snapshot) {
        // weather fallback은 마지막 성공 시각이 60분을 넘지 않아야만 유효하다.
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return Duration.between(snapshot.lastSucceededAt(), now).compareTo(LAST_SUCCESS_TTL) <= 0;
    }

    private CurrentWeatherResponse toResponse(WeatherSnapshot snapshot, boolean stale) {
        // 외부 응답에서는 snapshot 내부 구조를 그대로 노출하지 않고,
        // stale 여부와 forecast fallback 사용 여부만 함께 풀어서 전달한다.
        return new CurrentWeatherResponse(
                snapshot.weather(),
                snapshot.wind(),
                stale,
                snapshot.forecastFallbackUsed()
        );
    }
}
