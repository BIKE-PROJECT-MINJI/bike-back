package com.bikeprojectminji.bikeback.weather.service;

import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final Duration LAST_SUCCESS_TTL = Duration.ofMinutes(60);
    private static final String WEATHER_UNAVAILABLE_MESSAGE = "현재 날씨 정보를 사용할 수 없습니다.";
    private static final long DEFAULT_TOTAL_TIMEOUT_MS = 900;

    private final WeatherProviderPort weatherProviderPort;
    private final LastSuccessWeatherStore lastSuccessWeatherStore;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final ExecutorService weatherProviderExecutor;
    private final long weatherProviderTotalTimeoutMs;
    private final Clock clock;

    public WeatherService(
            WeatherProviderPort weatherProviderPort,
            LastSuccessWeatherStore lastSuccessWeatherStore,
            BikeMetricsRecorder bikeMetricsRecorder,
            ExecutorService weatherProviderExecutor,
            @Value("${weather.provider.total-timeout-ms:" + DEFAULT_TOTAL_TIMEOUT_MS + "}") long weatherProviderTotalTimeoutMs,
            Clock clock
    ) {
        this.weatherProviderPort = weatherProviderPort;
        this.lastSuccessWeatherStore = lastSuccessWeatherStore;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
        this.weatherProviderExecutor = weatherProviderExecutor;
        this.weatherProviderTotalTimeoutMs = weatherProviderTotalTimeoutMs;
        this.clock = clock;
    }

    public CurrentWeatherResponse getCurrent(BigDecimal lat, BigDecimal lon) {
        // 현재 날씨 조회는 provider 성공을 우선 사용하고,
        // 실패 시에는 last-success 캐시를 60분 범위 안에서만 fallback으로 허용한다.
        long startedAtNanos = System.nanoTime();
        WeatherLocationKey locationKey = WeatherLocationKey.from(lat, lon);
        Optional<WeatherSnapshot> fallback = lastSuccessWeatherStore.find(locationKey)
                .filter(this::isWithinLastSuccessTtl);

        if (fallback.isPresent()) {
            bikeMetricsRecorder.recordWeatherStaleServed();
            refreshWeatherAsync(locationKey, RequestLogContext.currentRequestId());
            log.info(
                    "weather_fallback_used request_id={} location_key={} provider_duration_ms={} total_duration_ms={} cache_age_ms={} forecast_fallback_used={} mode=stale_first",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    0,
                    toDurationMs(startedAtNanos),
                    cacheAgeMs(fallback.get()),
                    fallback.get().forecastFallbackUsed()
            );
            return toResponse(fallback.get(), true);
        }

        long providerStartedAtNanos = System.nanoTime();
        WeatherProviderResult providerResult = getProviderResult(locationKey);
        long providerDurationMs = toDurationMs(providerStartedAtNanos);

        if (providerResult.success() && providerResult.snapshot() != null) {
            lastSuccessWeatherStore.save(locationKey, providerResult.snapshot());
            log.info(
                    "weather_served request_id={} location_key={} source=provider provider_duration_ms={} total_duration_ms={} forecast_fallback_used={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    providerDurationMs,
                    toDurationMs(startedAtNanos),
                    providerResult.snapshot().forecastFallbackUsed()
            );
            return toResponse(providerResult.snapshot(), false);
        }

        log.info(
                "weather_unavailable request_id={} location_key={} provider_duration_ms={} total_duration_ms={}",
                RequestLogContext.currentRequestId(),
                locationKey,
                providerDurationMs,
                toDurationMs(startedAtNanos)
        );
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

    private long cacheAgeMs(WeatherSnapshot snapshot) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return Duration.between(snapshot.lastSucceededAt(), now).toMillis();
    }

    private long toDurationMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private WeatherProviderResult getProviderResult(WeatherLocationKey locationKey) {
        Future<WeatherProviderResult> future = weatherProviderExecutor.submit(() -> weatherProviderPort.getCurrent(locationKey));
        try {
            return future.get(weatherProviderTotalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            future.cancel(true);
            log.warn(
                    "weather_provider_timeout request_id={} location_key={} timeout_ms={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    weatherProviderTotalTimeoutMs
            );
            return WeatherProviderResult.failure();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn(
                    "weather_provider_interrupted request_id={} location_key={} timeout_ms={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    weatherProviderTotalTimeoutMs
            );
            return WeatherProviderResult.failure();
        } catch (ExecutionException executionException) {
            log.warn(
                    "weather_provider_execution_failure request_id={} location_key={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    executionException.getCause()
            );
            return WeatherProviderResult.failure();
        }
    }

    private void refreshWeatherAsync(WeatherLocationKey locationKey, String requestId) {
        weatherProviderExecutor.submit(() -> {
            WeatherProviderResult refreshed = weatherProviderPort.getCurrent(locationKey);
            if (refreshed.success() && refreshed.snapshot() != null) {
                lastSuccessWeatherStore.save(locationKey, refreshed.snapshot());
                log.info(
                        "weather_refresh_completed request_id={} location_key={} source=provider forecast_fallback_used={}",
                        requestId,
                        locationKey,
                        refreshed.snapshot().forecastFallbackUsed()
                );
                return;
            }

            log.info(
                    "weather_refresh_skipped request_id={} location_key={} reason=provider_failure",
                    requestId,
                    locationKey
            );
        });
    }
}
