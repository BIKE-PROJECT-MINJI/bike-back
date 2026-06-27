package com.bikeprojectminji.bikeback.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BikeMetricsRecorderTest {

    @Test
    @DisplayName("관측 메트릭 기록기는 fallback, undetermined, finalization failure 카운터를 누적한다")
    void recorderIncrementsExpectedCounters() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        BikeMetricsRecorder recorder = new BikeMetricsRecorder(meterRegistry);

        recorder.recordWeatherFallback();
        recorder.recordWeatherStaleServed();
        recorder.recordWeatherCacheHit("last_success_stale");
        recorder.recordWeatherCacheMiss();
        recorder.recordWeatherProviderResult("current", "success");
        recorder.recordWeatherProviderFailure("grace_timeout");
        recorder.recordWeatherProviderTimeout("primary");
        recorder.recordWeatherRequestCoalesced("sync_fetch");
        recorder.recordWeatherRefreshSkipped("already_in_progress");
        recorder.recordWeatherUnavailable("provider_failure");
        recorder.recordFeaturedCoursesFallback("missing_location_parameters");
        recorder.recordRidePolicyUndetermined("ACTIVE", "stale_location");
        recorder.recordRideRecordFinalizationFailure();
        recorder.recordRideFinalizationJobAcquired();
        recorder.recordRideFinalizationJobRetry("IllegalStateException");
        recorder.recordRideFinalizationJobTerminalFailure("IllegalStateException");
        recorder.recordRideFinalizationJobDuration("succeeded", java.time.Duration.ofMillis(125));
        recorder.recordCourseRouteCacheHit("ride_policy");
        recorder.recordCourseRouteCacheMiss("ride_policy");
        recorder.recordCourseRouteCacheBypass("course_download");
        recorder.recordCourseRouteSnapshotLoad("route_points");
        recorder.recordCourseRouteSnapshotLoadDuration("route_points", java.time.Duration.ofMillis(10));
        recorder.recordCourseRouteCacheEviction("route_points_updated");
        recorder.recordRoutingProviderFailure("GraphHopper", "http_429");
        recorder.recordProviderCall("GraphHopper", "route", "success", java.time.Duration.ofMillis(35));
        recorder.recordDatabaseBackpressureRejected("pool_exhausted");
        recorder.recordOperationDuration("ride.policy.evaluate", "success", java.time.Duration.ofMillis(42));

        assertThat(meterRegistry.get("bike_weather_fallback_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_stale_served_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_cache_hit_total").tag("mode", "last_success_stale").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_cache_miss_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_provider_result_total").tag("source", "current").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_provider_failure_total").tag("reason", "grace_timeout").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_provider_timeout_total").tag("phase", "primary").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_request_coalesced_total").tag("path", "sync_fetch").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_refresh_skipped_total").tag("reason", "already_in_progress").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_unavailable_total").tag("reason", "provider_failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_featured_courses_fallback_total")
                .tag("reason", "missing_location_parameters")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_ride_policy_undetermined_total")
                .tag("phase", "active")
                .tag("reason", "stale_location")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_ride_record_finalization_failed_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_ride_finalization_job_acquired_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_ride_finalization_job_retry_total")
                .tag("error_code", "illegalstateexception")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_ride_finalization_job_terminal_failure_total")
                .tag("error_code", "illegalstateexception")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_ride_finalization_job_duration")
                .tag("outcome", "succeeded")
                .timer()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get("bike_course_route_cache_hit_total").tag("consumer", "ride_policy").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_cache_miss_total").tag("consumer", "ride_policy").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_cache_bypass_total").tag("consumer", "course_download").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_snapshot_load_total").tag("consumer", "route_points").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_snapshot_load_duration").tag("consumer", "route_points").timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("bike_course_route_cache_eviction_total").tag("reason", "route_points_updated").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_routing_provider_failure_total")
                .tag("provider", "graphhopper")
                .tag("reason", "http_429")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_provider_call_total")
                .tag("provider", "graphhopper")
                .tag("operation", "route")
                .tag("outcome", "success")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_provider_latency")
                .tag("provider", "graphhopper")
                .tag("operation", "route")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.get("bike_database_backpressure_rejected_total")
                .tag("reason", "pool_exhausted")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_operation_duration")
                .tag("operation", "ride.policy.evaluate")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
    }
}
