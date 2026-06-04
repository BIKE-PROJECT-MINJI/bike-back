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
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private static final long PROVIDER_TIMEOUT_GRACE_MS = 300;

    private final WeatherProviderPort weatherProviderPort;
    private final LastSuccessWeatherStore lastSuccessWeatherStore;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final ExecutorService weatherProviderExecutor;
    private final long weatherProviderTotalTimeoutMs;
    private final Clock clock;
    private final ConcurrentMap<WeatherLocationKey, CompletableFuture<WeatherProviderResult>> inFlightProviderRequests;
    private final Set<WeatherLocationKey> refreshingLocations;

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
        this.inFlightProviderRequests = new ConcurrentHashMap<>();
        this.refreshingLocations = ConcurrentHashMap.newKeySet();
    }

    public CurrentWeatherResponse getCurrent(BigDecimal lat, BigDecimal lon) {
        // 현재 날씨 조회는 provider 성공을 우선 사용하고,
        // 실패 시에는 last-success 캐시를 60분 범위 안에서만 fallback으로 허용한다.
        long startedAtNanos = System.nanoTime();
        WeatherLocationKey locationKey = WeatherLocationKey.from(lat, lon);
        Optional<WeatherSnapshot> fallback = lastSuccessWeatherStore.find(locationKey)
                .filter(this::isWithinLastSuccessTtl);

        if (fallback.isPresent()) {
            bikeMetricsRecorder.recordWeatherCacheHit("last_success_stale");
            bikeMetricsRecorder.recordWeatherStaleServed();
            bikeMetricsRecorder.recordWeatherFallback();
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

        bikeMetricsRecorder.recordWeatherCacheMiss();

        long providerStartedAtNanos = System.nanoTime();
        WeatherProviderResult providerResult = getProviderResult(locationKey);
        long providerDurationMs = toDurationMs(providerStartedAtNanos);

        if (providerResult.success() && providerResult.snapshot() != null) {
            bikeMetricsRecorder.recordWeatherProviderResult(
                    providerResult.snapshot().forecastFallbackUsed() ? "hourly_fallback" : "current",
                    "success"
            );
            if (providerResult.snapshot().forecastFallbackUsed()) {
                bikeMetricsRecorder.recordWeatherFallback();
            }
            bikeMetricsRecorder.recordWeatherCacheHit("provider_success");
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
        bikeMetricsRecorder.recordWeatherUnavailable("provider_failure");
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
                snapshot.forecastFallbackUsed(),
                stale ? "STALE_LAST_SUCCESS" : "FRESH_PROVIDER",
                stale ? "LAST_SUCCESS_CACHE" : null,
                snapshot.observedAt(),
                stale ? Math.max(0L, cacheAgeMs(snapshot) / 1000L) : 0L
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
        CompletableFuture<WeatherProviderResult> future = getOrCreateProviderRequest(locationKey);
        try {
            return future.get(weatherProviderTotalTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            return recoverTimedOutProviderResult(future, locationKey);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            bikeMetricsRecorder.recordWeatherProviderFailure("interrupted");
            log.warn(
                    "weather_provider_interrupted request_id={} location_key={} timeout_ms={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    weatherProviderTotalTimeoutMs
            );
            return WeatherProviderResult.failure();
        } catch (ExecutionException executionException) {
            bikeMetricsRecorder.recordWeatherProviderFailure("execution_exception");
            log.warn(
                    "weather_provider_execution_failure request_id={} location_key={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    executionException.getCause()
            );
            return WeatherProviderResult.failure();
        }
    }

    private CompletableFuture<WeatherProviderResult> getOrCreateProviderRequest(WeatherLocationKey locationKey) {
        CompletableFuture<WeatherProviderResult> existing = inFlightProviderRequests.get(locationKey);
        if (existing != null) {
            bikeMetricsRecorder.recordWeatherRequestCoalesced("sync_fetch");
            return existing;
        }
        return inFlightProviderRequests.computeIfAbsent(locationKey, this::submitProviderRequest);
    }

    private CompletableFuture<WeatherProviderResult> submitProviderRequest(WeatherLocationKey locationKey) {
        CompletableFuture<WeatherProviderResult> future = CompletableFuture.supplyAsync(
                () -> weatherProviderPort.getCurrent(locationKey),
                weatherProviderExecutor
        );
        future.whenComplete((result, throwable) -> inFlightProviderRequests.remove(locationKey, future));
        return future;
    }

    private WeatherProviderResult recoverTimedOutProviderResult(Future<WeatherProviderResult> future, WeatherLocationKey locationKey) {
        bikeMetricsRecorder.recordWeatherProviderTimeout("primary");
        log.warn(
                "weather_provider_timeout request_id={} location_key={} timeout_ms={} grace_ms={}",
                RequestLogContext.currentRequestId(),
                locationKey,
                weatherProviderTotalTimeoutMs,
                PROVIDER_TIMEOUT_GRACE_MS
        );
        try {
            WeatherProviderResult recoveredResult = future.get(PROVIDER_TIMEOUT_GRACE_MS, TimeUnit.MILLISECONDS);
            if (recoveredResult.success() && recoveredResult.snapshot() != null) {
                bikeMetricsRecorder.recordWeatherProviderResult("grace_recovery", "success");
                log.info(
                        "weather_provider_timeout_recovered request_id={} location_key={} grace_ms={}",
                        RequestLogContext.currentRequestId(),
                        locationKey,
                        PROVIDER_TIMEOUT_GRACE_MS
                );
            }
            if (!recoveredResult.success() || recoveredResult.snapshot() == null) {
                bikeMetricsRecorder.recordWeatherProviderFailure("timeout_recovered_unsuccessful");
            }
            return recoveredResult;
        } catch (TimeoutException graceTimeoutException) {
            bikeMetricsRecorder.recordWeatherProviderTimeout("grace");
            bikeMetricsRecorder.recordWeatherProviderFailure("grace_timeout");
            return WeatherProviderResult.failure();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            bikeMetricsRecorder.recordWeatherProviderFailure("interrupted");
            log.warn(
                    "weather_provider_interrupted request_id={} location_key={} timeout_ms={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    weatherProviderTotalTimeoutMs + PROVIDER_TIMEOUT_GRACE_MS
            );
            return WeatherProviderResult.failure();
        } catch (ExecutionException executionException) {
            bikeMetricsRecorder.recordWeatherProviderFailure("execution_exception");
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
        if (!refreshingLocations.add(locationKey)) {
            bikeMetricsRecorder.recordWeatherRefreshSkipped("already_in_progress");
            log.info(
                    "weather_refresh_skipped request_id={} location_key={} reason=already_in_progress",
                    requestId,
                    locationKey
            );
            return;
        }

        CompletableFuture<WeatherProviderResult> future = getOrCreateProviderRequest(locationKey);
        future.whenComplete((refreshed, throwable) -> {
            refreshingLocations.remove(locationKey);
            if (throwable != null || refreshed == null || !refreshed.success() || refreshed.snapshot() == null) {
                bikeMetricsRecorder.recordWeatherRefreshSkipped("provider_failure");
                log.info(
                        "weather_refresh_skipped request_id={} location_key={} reason=provider_failure",
                        requestId,
                        locationKey
                );
                return;
            }

            bikeMetricsRecorder.recordWeatherProviderResult(
                    refreshed.snapshot().forecastFallbackUsed() ? "hourly_fallback" : "current",
                    "refresh_success"
            );
            if (refreshed.snapshot().forecastFallbackUsed()) {
                bikeMetricsRecorder.recordWeatherFallback();
            }
            lastSuccessWeatherStore.save(locationKey, refreshed.snapshot());
            log.info(
                    "weather_refresh_completed request_id={} location_key={} source=provider forecast_fallback_used={}",
                    requestId,
                    locationKey,
                    refreshed.snapshot().forecastFallbackUsed()
            );
        });
    }
}
