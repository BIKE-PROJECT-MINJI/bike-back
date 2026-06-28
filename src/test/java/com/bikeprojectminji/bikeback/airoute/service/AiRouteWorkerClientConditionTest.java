package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

class AiRouteWorkerClientConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(BikeMetricsRecorder.class, () -> new BikeMetricsRecorder(new SimpleMeterRegistry()))
            .withUserConfiguration(TestWorkerClientConfiguration.class);

    @Test
    @DisplayName("AI worker base URL이 비어 있으면 disabled worker를 사용한다")
    void useDisabledWorkerWhenBaseUrlIsBlank() {
        contextRunner
                .withPropertyValues("ai-route.worker.base-url=")
                .run(context -> assertThat(context.getBean(AiRouteWorkerClient.class))
                        .isInstanceOf(DisabledAiRouteWorkerClient.class));
    }

    @Test
    @DisplayName("AI worker base URL이 있으면 HTTP worker를 우선 사용한다")
    void useHttpWorkerWhenBaseUrlIsPresent() {
        contextRunner
                .withPropertyValues("ai-route.worker.base-url=http://localhost:8091")
                .run(context -> assertThat(context.getBean(AiRouteWorkerClient.class))
                        .isInstanceOf(HttpAiRouteWorkerClient.class));
    }

    @Import({
            DisabledAiRouteWorkerClient.class,
            HttpAiRouteWorkerClient.class
    })
    private static class TestWorkerClientConfiguration {
    }
}
