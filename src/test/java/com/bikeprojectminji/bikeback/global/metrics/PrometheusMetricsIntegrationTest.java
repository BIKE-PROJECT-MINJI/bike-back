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
        bikeMetricsRecorder.recordRidePolicyUndetermined("PRE_START", "low_accuracy");
        bikeMetricsRecorder.recordRideRecordFinalizationFailure();

        String scrape = prometheusMeterRegistry.scrape();

        assertThat(scrape).contains("bike_weather_fallback_total");
        assertThat(scrape).contains("bike_weather_stale_served_total");
        assertThat(scrape).contains("bike_ride_policy_undetermined_total");
        assertThat(scrape).contains("bike_ride_record_finalization_failed_total");
    }
}
