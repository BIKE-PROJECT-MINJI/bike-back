package com.bikeprojectminji.bikeback.global.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DatabaseBackpressureFilterTest {

    @Test
    @DisplayName("DB pool이 꽉 찬 API 요청은 chain 실행 없이 503으로 빠르게 거절한다")
    void rejectsApiRequestWhenPoolIsExhausted() throws ServletException, IOException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DatabaseBackpressureFilter filter = new DatabaseBackpressureFilter(
                () -> Optional.of(new DatabasePoolSnapshot(10, 0, 10, 0)),
                new DatabaseBackpressureProperties(),
                new BikeMetricsRecorder(meterRegistry),
                new ObjectMapper()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("데이터베이스 처리량이 일시적으로 부족합니다");
        assertThat(meterRegistry.get("bike_database_backpressure_rejected_total")
                .tag("reason", "pool_exhausted")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("API가 아니거나 pool 여유가 있으면 다음 filter로 넘긴다")
    void passesWhenPathIsNotGuardedOrPoolHasCapacity() throws ServletException, IOException {
        DatabaseBackpressureFilter filter = new DatabaseBackpressureFilter(
                () -> Optional.of(new DatabasePoolSnapshot(5, 5, 10, 0)),
                new DatabaseBackpressureProperties(),
                new BikeMetricsRecorder(new SimpleMeterRegistry()),
                new ObjectMapper()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
