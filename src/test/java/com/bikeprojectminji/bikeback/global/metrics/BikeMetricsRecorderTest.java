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
        recorder.recordFeaturedCoursesFallback("missing_location_parameters");
        recorder.recordRidePolicyUndetermined("ACTIVE", "stale_location");
        recorder.recordRideRecordFinalizationFailure();
        recorder.recordCourseRouteCacheHit("ride_policy");
        recorder.recordCourseRouteCacheMiss("ride_policy");
        recorder.recordCourseRouteCacheBypass("course_download");
        recorder.recordCourseRouteSnapshotLoad("route_points");
        recorder.recordCourseRouteCacheEviction("route_points_updated");

        assertThat(meterRegistry.get("bike_weather_fallback_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_weather_stale_served_total").counter().count()).isEqualTo(1.0);
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
        assertThat(meterRegistry.get("bike_course_route_cache_hit_total").tag("consumer", "ride_policy").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_cache_miss_total").tag("consumer", "ride_policy").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_cache_bypass_total").tag("consumer", "course_download").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_snapshot_load_total").tag("consumer", "route_points").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("bike_course_route_cache_eviction_total").tag("reason", "route_points_updated").counter().count()).isEqualTo(1.0);
    }
}
