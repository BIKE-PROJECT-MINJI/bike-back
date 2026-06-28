package com.bikeprojectminji.bikeback.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class BikeMetricsRecorder {

    private static final int MAX_TAG_VALUE_LENGTH = 60;

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

    public void recordWeatherCacheHit(String mode) {
        meterRegistry.counter("bike_weather_cache_hit_total", "mode", normalize(mode)).increment();
    }

    public void recordWeatherCacheMiss() {
        meterRegistry.counter("bike_weather_cache_miss_total").increment();
    }

    public void recordWeatherCacheFailure(String operation) {
        meterRegistry.counter("bike_weather_cache_failure_total", "operation", normalize(operation)).increment();
    }

    public void recordWeatherProviderResult(String source, String outcome) {
        meterRegistry.counter(
                "bike_weather_provider_result_total",
                "source", normalize(source),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordWeatherProviderFailure(String reason) {
        meterRegistry.counter("bike_weather_provider_failure_total", "reason", normalize(reason)).increment();
    }

    public void recordWeatherProviderTimeout(String phase) {
        meterRegistry.counter("bike_weather_provider_timeout_total", "phase", normalize(phase)).increment();
    }

    public void recordWeatherRequestCoalesced(String path) {
        meterRegistry.counter("bike_weather_request_coalesced_total", "path", normalize(path)).increment();
    }

    public void recordWeatherRefreshSkipped(String reason) {
        meterRegistry.counter("bike_weather_refresh_skipped_total", "reason", normalize(reason)).increment();
    }

    public void recordWeatherUnavailable(String reason) {
        meterRegistry.counter("bike_weather_unavailable_total", "reason", normalize(reason)).increment();
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

    public void recordRideFinalizationJobAcquired() {
        meterRegistry.counter("bike_ride_finalization_job_acquired_total").increment();
    }

    public void recordRideFinalizationJobRetry(String errorCode) {
        meterRegistry.counter(
                "bike_ride_finalization_job_retry_total",
                "error_code", normalize(errorCode)
        ).increment();
    }

    public void recordRideFinalizationJobTerminalFailure(String errorCode) {
        meterRegistry.counter(
                "bike_ride_finalization_job_terminal_failure_total",
                "error_code", normalize(errorCode)
        ).increment();
    }

    public void recordRideFinalizationJobDuration(String outcome, Duration duration) {
        meterRegistry.timer(
                "bike_ride_finalization_job_duration",
                "outcome", normalize(outcome)
        ).record(duration);
    }

    public void recordCourseRouteCacheHit(String consumer) {
        meterRegistry.counter("bike_course_route_cache_hit_total", "consumer", normalize(consumer)).increment();
    }

    public void recordCourseRouteCacheMiss(String consumer) {
        meterRegistry.counter("bike_course_route_cache_miss_total", "consumer", normalize(consumer)).increment();
    }

    public void recordCourseRouteCacheBypass(String consumer) {
        meterRegistry.counter("bike_course_route_cache_bypass_total", "consumer", normalize(consumer)).increment();
    }

    public void recordCourseRouteSnapshotLoad(String consumer) {
        meterRegistry.counter("bike_course_route_snapshot_load_total", "consumer", normalize(consumer)).increment();
    }

    public void recordCourseRouteSnapshotLoadDuration(String consumer, Duration duration) {
        meterRegistry.timer(
                "bike_course_route_snapshot_load_duration",
                "consumer", normalize(consumer)
        ).record(duration);
    }

    public void recordCourseRouteCacheEviction(String reason) {
        meterRegistry.counter("bike_course_route_cache_eviction_total", "reason", normalize(reason)).increment();
    }

    public void recordRoutingProviderFailure(String provider, String reason) {
        meterRegistry.counter(
                "bike_routing_provider_failure_total",
                "provider", normalize(provider),
                "reason", normalize(reason)
        ).increment();
    }

    public void recordProviderCall(String provider, String operation, String outcome, Duration duration) {
        meterRegistry.counter(
                "bike_provider_call_total",
                "provider", normalize(provider),
                "operation", normalize(operation),
                "outcome", normalize(outcome)
        ).increment();
        meterRegistry.timer(
                "bike_provider_latency",
                "provider", normalize(provider),
                "operation", normalize(operation),
                "outcome", normalize(outcome)
        ).record(duration);
    }

    public void recordDatabaseBackpressureRejected(String reason) {
        meterRegistry.counter(
                "bike_database_backpressure_rejected_total",
                "reason", normalize(reason)
        ).increment();
    }

    public void recordRideSaveConcurrencyGate(String outcome) {
        meterRegistry.counter(
                "bike_ride_save_concurrency_gate_total",
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordIdempotencyLock(String operation, String outcome) {
        meterRegistry.counter(
                "bike_idempotency_lock_total",
                "operation", normalize(operation),
                "outcome", normalize(outcome)
        ).increment();
    }

    public void recordIdempotencyLockWaitDuration(String operation, String outcome, Duration duration) {
        meterRegistry.timer(
                "bike_idempotency_lock_wait_duration",
                "operation", normalize(operation),
                "outcome", normalize(outcome)
        ).record(duration);
    }

    public void recordOperationDuration(String operation, String outcome, Duration duration) {
        Timer.builder("bike_operation_duration")
                .tag("operation", normalize(operation))
                .tag("outcome", normalize(outcome))
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim()
                .toLowerCase()
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_.:-]", "_");
        if (normalized.length() > MAX_TAG_VALUE_LENGTH) {
            return normalized.substring(0, MAX_TAG_VALUE_LENGTH);
        }
        return normalized;
    }
}
