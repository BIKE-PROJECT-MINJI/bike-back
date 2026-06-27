package com.bikeprojectminji.bikeback.global.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatabaseBackpressurePropertiesTest {

    @Test
    @DisplayName("DB backpressure는 API 경로만 보호한다")
    void guardsOnlyApiPaths() {
        DatabaseBackpressureProperties properties = new DatabaseBackpressureProperties();

        assertThat(properties.shouldGuardPath("/api/v1/courses")).isTrue();
        assertThat(properties.shouldGuardPath("/health")).isFalse();
        assertThat(properties.shouldGuardPath("/actuator/prometheus")).isFalse();
    }

    @Test
    @DisplayName("Hikari pool이 꽉 차거나 pending thread가 있으면 요청을 거절한다")
    void rejectsWhenPoolIsExhaustedOrPending() {
        DatabaseBackpressureProperties properties = new DatabaseBackpressureProperties();

        assertThat(properties.shouldReject(new DatabasePoolSnapshot(10, 0, 10, 0))).isTrue();
        assertThat(properties.shouldReject(new DatabasePoolSnapshot(7, 1, 10, 1))).isTrue();
        assertThat(properties.shouldReject(new DatabasePoolSnapshot(8, 2, 10, 0))).isFalse();
    }

    @Test
    @DisplayName("비활성화하면 어떤 snapshot도 거절하지 않는다")
    void disabledDoesNotReject() {
        DatabaseBackpressureProperties properties = new DatabaseBackpressureProperties();
        properties.setEnabled(false);

        assertThat(properties.shouldGuardPath("/api/v1/courses")).isFalse();
        assertThat(properties.shouldReject(new DatabasePoolSnapshot(10, 0, 10, 25))).isFalse();
    }
}
