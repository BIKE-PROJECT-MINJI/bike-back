package com.bikeprojectminji.bikeback.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ObservabilityLoggingPropertiesTest {

    @Test
    @DisplayName("기본 HTTP 로그 정책은 느린 요청과 오류만 남긴다")
    void defaultHttpPolicyLogsOnlySlowOrError() {
        ObservabilityLoggingProperties properties = new ObservabilityLoggingProperties();

        assertThat(properties.shouldLogHttpRequest(200, 100)).isFalse();
        assertThat(properties.shouldLogHttpRequest(200, 500)).isTrue();
        assertThat(properties.shouldLogHttpRequest(404, 10)).isTrue();
        assertThat(properties.shouldLogHttpRequest(500, 10)).isTrue();
    }

    @Test
    @DisplayName("기본 operation 로그 정책은 느린 내부 로직과 실패만 남긴다")
    void defaultOperationPolicyLogsOnlySlowOrFailure() {
        ObservabilityLoggingProperties properties = new ObservabilityLoggingProperties();

        assertThat(properties.shouldLogOperation("success", 50)).isFalse();
        assertThat(properties.shouldLogOperation("success", 200)).isTrue();
        assertThat(properties.shouldLogOperation("failure", 1)).isTrue();
    }

    @Test
    @DisplayName("sample rate 1이면 빠른 성공 요청도 샘플로 남긴다")
    void sampleRateOneLogsFastSuccess() {
        ObservabilityLoggingProperties properties = new ObservabilityLoggingProperties();
        properties.getHttp().setSampleRate(1.0d);
        properties.getOperation().setSampleRate(1.0d);

        assertThat(properties.shouldLogHttpRequest(200, 1)).isTrue();
        assertThat(properties.shouldLogOperation("success", 1)).isTrue();
    }
}
