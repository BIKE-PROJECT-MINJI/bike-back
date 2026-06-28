package com.bikeprojectminji.bikeback.weather.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    @Mock
    private WeatherProviderPort weatherProviderPort;

    @Mock
    private LastSuccessWeatherStore lastSuccessWeatherStore;

    @Mock
    private BikeMetricsRecorder bikeMetricsRecorder;

    private ExecutorService weatherProviderExecutor;

    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        weatherProviderExecutor = Executors.newSingleThreadExecutor();
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                900,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
    }

    @AfterEach
    void tearDown() {
        weatherProviderExecutor.shutdownNow();
    }

    @Test
    @DisplayName("provider 성공이면 stale=false로 응답하고 마지막 성공값을 저장한다")
    void getCurrentReturnsFreshResponse() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.success(snapshot));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isFalse();
        assertThat(response.freshnessStatus()).isEqualTo("FRESH_PROVIDER");
        assertThat(response.staleReason()).isNull();
        assertThat(response.observedAt()).isEqualTo(OffsetDateTime.parse("2026-03-29T10:19:00+09:00"));
        assertThat(response.cacheAgeSec()).isZero();
        assertThat(response.forecastFallbackUsed()).isFalse();
        verify(lastSuccessWeatherStore).save(key, snapshot);
    }

    @Test
    @DisplayName("5분 이내 구역 캐시가 있으면 provider를 호출하지 않고 fresh cache로 응답한다")
    void getCurrentReturnsFreshGridCacheResponse() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(snapshot(false, "2026-03-29T10:16:00+09:00")));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isFalse();
        assertThat(response.freshnessStatus()).isEqualTo("FRESH_CACHE");
        assertThat(response.staleReason()).isNull();
        assertThat(response.cacheAgeSec()).isEqualTo(240);
        verify(weatherProviderPort, never()).getCurrent(key);
        verify(bikeMetricsRecorder).recordWeatherCacheHit("fresh_grid");
    }

    @Test
    @DisplayName("5분을 넘고 60분 이내 구역 캐시가 있으면 stale=true로 응답하고 비동기 refresh를 건다")
    void getCurrentReturnsStaleFallbackResponse() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        lenient().when(weatherProviderPort.getCurrent(key)).thenReturn(WeatherProviderResult.failure());
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(snapshot(true, "2026-03-29T09:40:00+09:00")));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isTrue();
        assertThat(response.freshnessStatus()).isEqualTo("STALE_LAST_SUCCESS");
        assertThat(response.staleReason()).isEqualTo("LAST_SUCCESS_CACHE");
        assertThat(response.cacheAgeSec()).isEqualTo(2400);
        assertThat(response.forecastFallbackUsed()).isTrue();
        verify(bikeMetricsRecorder).recordWeatherStaleServed();
        verify(bikeMetricsRecorder).recordWeatherFallback();
    }

    @Test
    @DisplayName("5분을 넘고 60분 이내 구역 캐시가 있으면 stale 응답을 먼저 반환하고 provider refresh는 비동기로 수행한다")
    void getCurrentReturnsStaleFirstAndRefreshesAsync() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot staleSnapshot = snapshot(true, "2026-03-29T09:40:00+09:00");
        WeatherSnapshot refreshedSnapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(staleSnapshot));
        given(weatherProviderPort.getCurrent(key)).willAnswer(invocation -> {
            Thread.sleep(150);
            return WeatherProviderResult.success(refreshedSnapshot);
        });

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isTrue();
        assertThat(response.forecastFallbackUsed()).isTrue();
        verify(lastSuccessWeatherStore, timeout(1000)).save(key, refreshedSnapshot);
    }

    @Test
    @DisplayName("같은 좌표의 동시 cold 요청은 provider 호출을 한 번으로 합친다")
    void getCurrentCoalescesConcurrentColdRequests() throws Exception {
        weatherProviderExecutor = Executors.newFixedThreadPool(4);
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                900,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );

        BigDecimal lat = BigDecimal.valueOf(37.5665);
        BigDecimal lon = BigDecimal.valueOf(126.9780);
        WeatherLocationKey key = WeatherLocationKey.from(lat, lon);
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        AtomicInteger providerCallCount = new AtomicInteger();
        CountDownLatch providerStarted = new CountDownLatch(1);

        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());
        given(weatherProviderPort.getCurrent(key)).willAnswer(invocation -> {
            providerCallCount.incrementAndGet();
            providerStarted.countDown();
            Thread.sleep(200);
            return WeatherProviderResult.success(snapshot);
        });

        ExecutorService callerExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<CurrentWeatherResponse> first = callerExecutor.submit(() -> weatherService.getCurrent(lat, lon));
            providerStarted.await(1, TimeUnit.SECONDS);
            Future<CurrentWeatherResponse> second = callerExecutor.submit(() -> weatherService.getCurrent(lat, lon));

            CurrentWeatherResponse firstResponse = first.get(2, TimeUnit.SECONDS);
            CurrentWeatherResponse secondResponse = second.get(2, TimeUnit.SECONDS);

            assertThat(firstResponse.stale()).isFalse();
            assertThat(secondResponse.stale()).isFalse();
            assertThat(providerCallCount.get()).isEqualTo(1);
            verify(lastSuccessWeatherStore, timeout(1000).atLeastOnce()).save(key, snapshot);
        } finally {
            callerExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 좌표의 stale refresh는 중복 실행하지 않는다")
    void getCurrentDeduplicatesStaleRefresh() throws Exception {
        weatherProviderExecutor = Executors.newFixedThreadPool(4);
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                900,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );

        BigDecimal lat = BigDecimal.valueOf(37.5665);
        BigDecimal lon = BigDecimal.valueOf(126.9780);
        WeatherLocationKey key = WeatherLocationKey.from(lat, lon);
        WeatherSnapshot staleSnapshot = snapshot(true, "2026-03-29T09:40:00+09:00");
        WeatherSnapshot refreshedSnapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        AtomicInteger providerCallCount = new AtomicInteger();
        CountDownLatch providerStarted = new CountDownLatch(1);

        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(staleSnapshot));
        given(weatherProviderPort.getCurrent(key)).willAnswer(invocation -> {
            providerCallCount.incrementAndGet();
            providerStarted.countDown();
            Thread.sleep(200);
            return WeatherProviderResult.success(refreshedSnapshot);
        });

        CurrentWeatherResponse first = weatherService.getCurrent(lat, lon);
        providerStarted.await(1, TimeUnit.SECONDS);
        CurrentWeatherResponse second = weatherService.getCurrent(lat, lon);

        assertThat(first.stale()).isTrue();
        assertThat(second.stale()).isTrue();
        assertThat(providerCallCount.get()).isEqualTo(1);
        verify(lastSuccessWeatherStore, timeout(1000)).save(key, refreshedSnapshot);
    }

    @Test
    @DisplayName("provider가 forecast fallback snapshot을 반환하면 weather fallback 메트릭을 기록한다")
    void getCurrentRecordsWeatherFallbackWhenProviderUsesForecastFallback() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.success(snapshot(true, "2026-03-29T10:19:00+09:00")));
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isFalse();
        assertThat(response.forecastFallbackUsed()).isTrue();
        verify(bikeMetricsRecorder).recordWeatherFallback();
    }

    @Test
    @DisplayName("provider 실패 시 마지막 성공값이 60분을 넘기면 unavailable metadata로 응답한다")
    void getCurrentReturnsUnavailableWhenLastSuccessExpired() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.failure());
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(snapshot(false, "2026-03-29T09:10:00+09:00")));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.weather()).isNull();
        assertThat(response.wind()).isNull();
        assertThat(response.stale()).isFalse();
        assertThat(response.freshnessStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.staleReason()).isEqualTo("PROVIDER_FAILURE");
        verify(bikeMetricsRecorder).recordWeatherUnavailable("provider_failure");
    }

    @Test
    @DisplayName("provider 실패 시 마지막 성공값이 없으면 unavailable metadata로 응답한다")
    void getCurrentReturnsUnavailableWhenLastSuccessMissing() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.failure());
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.weather()).isNull();
        assertThat(response.wind()).isNull();
        assertThat(response.stale()).isFalse();
        assertThat(response.freshnessStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.staleReason()).isEqualTo("PROVIDER_FAILURE");
        verify(bikeMetricsRecorder).recordWeatherUnavailable("provider_failure");
    }

    @Test
    @DisplayName("Redis cache read 실패는 provider 조회로 복구하고 raw 500으로 전파하지 않는다")
    void getCurrentFallsBackToProviderWhenCacheReadFails() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(lastSuccessWeatherStore.find(key)).willThrow(new IllegalStateException("redis down"));
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.success(snapshot));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.freshnessStatus()).isEqualTo("FRESH_PROVIDER");
        assertThat(response.weather()).isNotNull();
        verify(bikeMetricsRecorder).recordWeatherCacheFailure("read");
    }

    @Test
    @DisplayName("Redis cache write 실패는 provider 성공 응답을 실패시키지 않는다")
    void getCurrentKeepsProviderResponseWhenCacheWriteFails() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.success(snapshot));
        org.mockito.BDDMockito.willThrow(new IllegalStateException("redis down"))
                .given(lastSuccessWeatherStore).save(key, snapshot);

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.freshnessStatus()).isEqualTo("FRESH_PROVIDER");
        assertThat(response.weather()).isNotNull();
        verify(bikeMetricsRecorder).recordWeatherCacheFailure("write_provider_success");
    }

    @Test
    @DisplayName("provider executor가 포화되어 reject되면 unavailable metadata로 응답한다")
    void getCurrentReturnsUnavailableWhenProviderBulkheadRejects() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                new RejectingExecutorService(),
                50,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.freshnessStatus()).isEqualTo("UNAVAILABLE");
        verify(bikeMetricsRecorder).recordWeatherProviderFailure("bulkhead_rejected");
        verify(bikeMetricsRecorder).recordWeatherUnavailable("provider_failure");
    }

    @Test
    @DisplayName("provider 총 요청 시간이 초과되면 60분 이내 마지막 성공값으로 fallback한다")
    void getCurrentReturnsStaleFallbackWhenProviderTimesOut() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        weatherService = new WeatherService(
                locationKey -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return WeatherProviderResult.failure();
                    }
                    return WeatherProviderResult.success(snapshot(false, "2026-03-29T10:19:00+09:00"));
                },
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                50,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(snapshot(true, "2026-03-29T09:40:00+09:00")));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isTrue();
        assertThat(response.forecastFallbackUsed()).isTrue();
        verify(bikeMetricsRecorder).recordWeatherStaleServed();
        verify(bikeMetricsRecorder).recordWeatherFallback();
    }

    @Test
    @DisplayName("provider가 timeout 직후 짧은 지연 안에 성공하면 fresh 응답으로 회복한다")
    void getCurrentReturnsFreshResponseWhenProviderCompletesWithinGraceWindow() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        weatherService = new WeatherService(
                locationKey -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return WeatherProviderResult.failure();
                    }
                    return WeatherProviderResult.success(snapshot(false, "2026-03-29T10:19:00+09:00"));
                },
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                900,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isFalse();
        assertThat(response.forecastFallbackUsed()).isFalse();
        verify(lastSuccessWeatherStore).save(key, snapshot(false, "2026-03-29T10:19:00+09:00"));
    }

    @Test
    @DisplayName("cold 요청이 grace timeout 이후 성공해도 늦은 성공값을 구역 캐시에 저장한다")
    void getCurrentWarmsCacheWhenProviderCompletesAfterGraceTimeout() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        weatherService = new WeatherService(
                locationKey -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return WeatherProviderResult.failure();
                    }
                    return WeatherProviderResult.success(snapshot);
                },
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                50,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.empty());

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.freshnessStatus()).isEqualTo("UNAVAILABLE");
        verify(lastSuccessWeatherStore, timeout(1000)).save(key, snapshot);
        verify(bikeMetricsRecorder, timeout(1000)).recordWeatherProviderResult("current", "late_success");
    }

    @Test
    @DisplayName("현재 권역 조회 성공 후 인접 권역을 사용자 응답과 분리해 prewarm한다")
    void getCurrentPrewarmsAdjacentLocationsAfterProviderSuccess() {
        weatherProviderExecutor = Executors.newFixedThreadPool(4);
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                900,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
        WeatherLocationKey currentKey = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(lastSuccessWeatherStore.find(currentKey)).willReturn(Optional.empty());
        given(weatherProviderPort.getCurrent(org.mockito.ArgumentMatchers.any(WeatherLocationKey.class)))
                .willReturn(WeatherProviderResult.success(snapshot));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.freshnessStatus()).isEqualTo("FRESH_PROVIDER");
        for (WeatherLocationKey adjacentKey : currentKey.adjacentKeys()) {
            verify(lastSuccessWeatherStore, timeout(1500)).find(adjacentKey);
            verify(lastSuccessWeatherStore, timeout(1500)).save(adjacentKey, snapshot);
        }
    }

    @Test
    @DisplayName("fresh cache가 있는 인접 권역은 prewarm provider 호출을 건너뛴다")
    void getCurrentSkipsAdjacentPrewarmWhenFreshCacheExists() {
        weatherProviderExecutor = Executors.newFixedThreadPool(4);
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                bikeMetricsRecorder,
                weatherProviderExecutor,
                900,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
        WeatherLocationKey currentKey = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherLocationKey freshAdjacentKey = currentKey.adjacentKeys().get(0);
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(lastSuccessWeatherStore.find(currentKey)).willReturn(Optional.empty());
        given(lastSuccessWeatherStore.find(freshAdjacentKey)).willReturn(Optional.of(snapshot(false, "2026-03-29T10:18:00+09:00")));
        given(weatherProviderPort.getCurrent(org.mockito.ArgumentMatchers.any(WeatherLocationKey.class)))
                .willReturn(WeatherProviderResult.success(snapshot));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.freshnessStatus()).isEqualTo("FRESH_PROVIDER");
        verify(bikeMetricsRecorder, timeout(1500)).recordWeatherRefreshSkipped("prewarm_fresh_cache");
        verify(weatherProviderPort, timeout(1500).atLeastOnce()).getCurrent(org.mockito.ArgumentMatchers.argThat(key -> !freshAdjacentKey.equals(key)));
    }

    private WeatherSnapshot snapshot(boolean forecastFallbackUsed, String lastSucceededAt) {
        return new WeatherSnapshot(
                new WeatherData(12, "clear", "none"),
                new WindData(14, "북서", 315),
                forecastFallbackUsed,
                OffsetDateTime.parse(lastSucceededAt),
                OffsetDateTime.parse(lastSucceededAt)
        );
    }

    private static class RejectingExecutorService extends AbstractExecutorService {

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("weather provider executor rejected");
        }
    }
}
