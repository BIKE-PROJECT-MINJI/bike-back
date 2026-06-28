package com.bikeprojectminji.bikeback.weather.service;

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
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final Duration FRESH_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration LAST_SUCCESS_TTL = Duration.ofMinutes(60);
    private static final Duration PREWARM_ATTEMPT_TTL = Duration.ofMinutes(5);
    private static final long DEFAULT_TOTAL_TIMEOUT_MS = 900;
    private static final long PROVIDER_TIMEOUT_GRACE_MS = 300;

    private final WeatherProviderPort weatherProviderPort;
    private final LastSuccessWeatherStore lastSuccessWeatherStore;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final ExecutorService weatherProviderExecutor;
    private final long weatherProviderTotalTimeoutMs;
    private final Clock clock;
    private final ConcurrentMap<WeatherLocationKey, CompletableFuture<WeatherProviderResult>> inFlightProviderRequests;
    private final ConcurrentMap<WeatherLocationKey, OffsetDateTime> prewarmAttemptedAt;
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
        this.prewarmAttemptedAt = new ConcurrentHashMap<>();
        this.refreshingLocations = ConcurrentHashMap.newKeySet();
    }

    public CurrentWeatherResponse getCurrent(BigDecimal lat, BigDecimal lon) {
        // 현재 날씨는 구역 단위 캐시를 우선 사용한다.
        // 5분 이내 fresh cache는 외부 provider를 호출하지 않고, 60분 이내 값은 stale fallback으로 즉시 반환한다.
        long startedAtNanos = System.nanoTime();
        WeatherLocationKey locationKey = WeatherLocationKey.from(lat, lon);
        Optional<WeatherSnapshot> cached = findCachedSnapshot(locationKey)
                .filter(this::isWithinLastSuccessTtl);

        if (cached.isPresent() && isWithinFreshCacheTtl(cached.get())) {
            bikeMetricsRecorder.recordWeatherCacheHit("fresh_grid");
            log.info(
                    "weather_cache_hit request_id={} location_key={} total_duration_ms={} cache_age_ms={} mode=fresh_grid",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    toDurationMs(startedAtNanos),
                    cacheAgeMs(cached.get())
            );
            prewarmAdjacentLocations(locationKey, RequestLogContext.currentRequestId());
            return toResponse(cached.get(), false, "FRESH_CACHE", null);
        }

        if (cached.isPresent()) {
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
                    cacheAgeMs(cached.get()),
                    cached.get().forecastFallbackUsed()
            );
            prewarmAdjacentLocations(locationKey, RequestLogContext.currentRequestId());
            return toResponse(cached.get(), true, "STALE_LAST_SUCCESS", "LAST_SUCCESS_CACHE");
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
            saveSnapshot(locationKey, providerResult.snapshot(), "provider_success");
            log.info(
                    "weather_served request_id={} location_key={} source=provider provider_duration_ms={} total_duration_ms={} forecast_fallback_used={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    providerDurationMs,
                    toDurationMs(startedAtNanos),
                    providerResult.snapshot().forecastFallbackUsed()
            );
            prewarmAdjacentLocations(locationKey, RequestLogContext.currentRequestId());
            return toResponse(providerResult.snapshot(), false, "FRESH_PROVIDER", null);
        }

        log.info(
                "weather_unavailable request_id={} location_key={} provider_duration_ms={} total_duration_ms={}",
                RequestLogContext.currentRequestId(),
                locationKey,
                providerDurationMs,
                toDurationMs(startedAtNanos)
        );
        bikeMetricsRecorder.recordWeatherUnavailable("provider_failure");
        return unavailableResponse();
    }

    private boolean isWithinFreshCacheTtl(WeatherSnapshot snapshot) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return Duration.between(snapshot.lastSucceededAt(), now).compareTo(FRESH_CACHE_TTL) <= 0;
    }

    private Optional<WeatherSnapshot> findCachedSnapshot(WeatherLocationKey locationKey) {
        try {
            return lastSuccessWeatherStore.find(locationKey);
        } catch (RuntimeException exception) {
            bikeMetricsRecorder.recordWeatherCacheFailure("read");
            log.warn(
                    "weather_cache_failure request_id={} location_key={} operation=read",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    exception
            );
            return Optional.empty();
        }
    }

    private void saveSnapshot(WeatherLocationKey locationKey, WeatherSnapshot snapshot, String operation) {
        try {
            lastSuccessWeatherStore.save(locationKey, snapshot);
        } catch (RuntimeException exception) {
            bikeMetricsRecorder.recordWeatherCacheFailure("write_" + operation);
            log.warn(
                    "weather_cache_failure request_id={} location_key={} operation=write_{}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    operation,
                    exception
            );
        }
    }

    private boolean isWithinLastSuccessTtl(WeatherSnapshot snapshot) {
        // weather fallback은 마지막 성공 시각이 60분을 넘지 않아야만 유효하다.
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return Duration.between(snapshot.lastSucceededAt(), now).compareTo(LAST_SUCCESS_TTL) <= 0;
    }

    private CurrentWeatherResponse toResponse(
            WeatherSnapshot snapshot,
            boolean stale,
            String freshnessStatus,
            String staleReason
    ) {
        // 외부 응답에서는 snapshot 내부 구조를 그대로 노출하지 않고,
        // stale 여부와 forecast fallback 사용 여부만 함께 풀어서 전달한다.
        return new CurrentWeatherResponse(
                snapshot.weather(),
                snapshot.wind(),
                stale,
                snapshot.forecastFallbackUsed(),
                freshnessStatus,
                staleReason,
                snapshot.observedAt(),
                "FRESH_PROVIDER".equals(freshnessStatus) ? 0L : Math.max(0L, cacheAgeMs(snapshot) / 1000L)
        );
    }

    private CurrentWeatherResponse unavailableResponse() {
        return new CurrentWeatherResponse(
                null,
                null,
                false,
                false,
                "UNAVAILABLE",
                "PROVIDER_FAILURE",
                null,
                0L
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
        } finally {
            if (future.isDone()) {
                inFlightProviderRequests.remove(locationKey, future);
            }
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
        CompletableFuture<WeatherProviderResult> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> weatherProviderPort.getCurrent(locationKey),
                    weatherProviderExecutor
            );
        } catch (RejectedExecutionException rejectedExecutionException) {
            bikeMetricsRecorder.recordWeatherProviderFailure("bulkhead_rejected");
            log.warn(
                    "weather_provider_bulkhead_rejected request_id={} location_key={}",
                    RequestLogContext.currentRequestId(),
                    locationKey,
                    rejectedExecutionException
            );
            return CompletableFuture.completedFuture(WeatherProviderResult.failure());
        }
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
            cacheLateProviderSuccess(future, locationKey, RequestLogContext.currentRequestId());
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

    private void cacheLateProviderSuccess(Future<WeatherProviderResult> future, WeatherLocationKey locationKey, String requestId) {
        if (!(future instanceof CompletableFuture<?> rawFuture)) {
            return;
        }
        @SuppressWarnings("unchecked")
        CompletableFuture<WeatherProviderResult> completableFuture = (CompletableFuture<WeatherProviderResult>) rawFuture;
        completableFuture.thenAccept(result -> {
            if (result == null || !result.success() || result.snapshot() == null) {
                return;
            }
            bikeMetricsRecorder.recordWeatherProviderResult(
                    result.snapshot().forecastFallbackUsed() ? "hourly_fallback" : "current",
                    "late_success"
            );
            if (result.snapshot().forecastFallbackUsed()) {
                bikeMetricsRecorder.recordWeatherFallback();
            }
            saveSnapshot(locationKey, result.snapshot(), "late_success");
            log.info(
                    "weather_late_success_cached request_id={} location_key={} forecast_fallback_used={}",
                    requestId,
                    locationKey,
                    result.snapshot().forecastFallbackUsed()
            );
        });
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
            saveSnapshot(locationKey, refreshed.snapshot(), "refresh_success");
            log.info(
                    "weather_refresh_completed request_id={} location_key={} source=provider forecast_fallback_used={}",
                    requestId,
                    locationKey,
                    refreshed.snapshot().forecastFallbackUsed()
            );
        });
    }

    private void prewarmAdjacentLocations(WeatherLocationKey locationKey, String requestId) {
        try {
            CompletableFuture.runAsync(
                    () -> locationKey.adjacentKeys().forEach(adjacentKey -> prewarmLocation(adjacentKey, requestId)),
                    weatherProviderExecutor
            );
        } catch (RejectedExecutionException rejectedExecutionException) {
            bikeMetricsRecorder.recordWeatherRefreshSkipped("prewarm_bulkhead_rejected");
            log.info(
                    "weather_prewarm_skipped request_id={} location_key={} reason=bulkhead_rejected",
                    requestId,
                    locationKey,
                    rejectedExecutionException
            );
        }
    }

    private void prewarmLocation(WeatherLocationKey locationKey, String requestId) {
        if (wasRecentlyPrewarmed(locationKey)) {
            bikeMetricsRecorder.recordWeatherRefreshSkipped("prewarm_recent_attempt");
            return;
        }
        prewarmAttemptedAt.put(locationKey, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));

        Optional<WeatherSnapshot> cached = findCachedSnapshot(locationKey);
        if (cached.isPresent() && isWithinFreshCacheTtl(cached.get())) {
            bikeMetricsRecorder.recordWeatherRefreshSkipped("prewarm_fresh_cache");
            return;
        }

        refreshWeatherAsync(locationKey, requestId);
    }

    private boolean wasRecentlyPrewarmed(WeatherLocationKey locationKey) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime attemptedAt = prewarmAttemptedAt.get(locationKey);
        if (attemptedAt == null) {
            return false;
        }
        if (Duration.between(attemptedAt, now).compareTo(PREWARM_ATTEMPT_TTL) <= 0) {
            return true;
        }
        prewarmAttemptedAt.remove(locationKey, attemptedAt);
        return false;
    }
}
