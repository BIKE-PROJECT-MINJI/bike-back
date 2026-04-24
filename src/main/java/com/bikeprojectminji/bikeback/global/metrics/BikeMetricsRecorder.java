package com.bikeprojectminji.bikeback.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BikeMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public BikeMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordWeatherFallback() {
        meterRegistry.counter("bike_weather_fallback_total").increment();
    }

    public void recordWeatherStaleServed() {
        meterRegistry.counter("bike_weather_stale_served_total").increment();
    }

    public void recordFeaturedCoursesFallback(String reason) {
        meterRegistry.counter("bike_featured_courses_fallback_total", "reason", normalize(reason)).increment();
    }

    public void recordRidePolicyUndetermined(String phase, String reason) {
        meterRegistry.counter(
                "bike_ride_policy_undetermined_total",
                "phase", normalize(phase),
                "reason", normalize(reason)
        ).increment();
    }

    public void recordRideRecordFinalizationFailure() {
        meterRegistry.counter("bike_ride_record_finalization_failed_total").increment();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase().replace(' ', '_');
    }
}
