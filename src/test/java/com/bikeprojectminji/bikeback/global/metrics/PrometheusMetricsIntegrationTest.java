package com.bikeprojectminji.bikeback.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PrometheusMetricsIntegrationTest {

    @Test
    @DisplayName("프로메테우스 레지스트리는 BIKE 커스텀 메트릭을 scrape 결과에 포함한다")
    void prometheusRegistryContainsBikeCustomMetrics() {
        PrometheusMeterRegistry prometheusMeterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        BikeMetricsRecorder bikeMetricsRecorder = new BikeMetricsRecorder(prometheusMeterRegistry);

        bikeMetricsRecorder.recordWeatherFallback();
        bikeMetricsRecorder.recordWeatherStaleServed();
        bikeMetricsRecorder.recordWeatherCacheHit("last_success_stale");
        bikeMetricsRecorder.recordWeatherProviderTimeout("primary");
        bikeMetricsRecorder.recordWeatherRefreshSkipped("already_in_progress");
        bikeMetricsRecorder.recordRidePolicyUndetermined("PRE_START", "low_accuracy");
        bikeMetricsRecorder.recordRideRecordFinalizationFailure();
        bikeMetricsRecorder.recordRideFinalizationJobAcquired();
        bikeMetricsRecorder.recordRideFinalizationJobRetry("IllegalStateException");
        bikeMetricsRecorder.recordRideFinalizationJobDuration("succeeded", java.time.Duration.ofMillis(125));
        bikeMetricsRecorder.recordCourseRouteCacheHit("ride_policy");
        bikeMetricsRecorder.recordCourseRouteSnapshotLoad("ride_policy");
        bikeMetricsRecorder.recordProviderCall("GraphHopper", "route", "success", java.time.Duration.ofMillis(35));
        bikeMetricsRecorder.recordOperationDuration("ride.policy.evaluate", "success", java.time.Duration.ofMillis(42));

        String scrape = prometheusMeterRegistry.scrape();

        assertThat(scrape).contains("bike_weather_fallback_total");
        assertThat(scrape).contains("bike_weather_stale_served_total");
        assertThat(scrape).contains("bike_weather_cache_hit_total");
        assertThat(scrape).contains("bike_weather_provider_timeout_total");
        assertThat(scrape).contains("bike_weather_refresh_skipped_total");
        assertThat(scrape).contains("bike_ride_policy_undetermined_total");
        assertThat(scrape).contains("bike_ride_record_finalization_failed_total");
        assertThat(scrape).contains("bike_ride_finalization_job_acquired_total");
        assertThat(scrape).contains("bike_ride_finalization_job_retry_total");
        assertThat(scrape).contains("bike_ride_finalization_job_duration");
        assertThat(scrape).contains("bike_course_route_cache_hit_total");
        assertThat(scrape).contains("bike_course_route_snapshot_load_total");
        assertThat(scrape).contains("bike_provider_call_total");
        assertThat(scrape).contains("bike_provider_latency");
        assertThat(scrape).contains("bike_operation_duration");
    }
}
