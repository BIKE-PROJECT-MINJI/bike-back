package com.bikeprojectminji.bikeback.global.health;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.global.monitor.DependencyStatusResponse;
import com.bikeprojectminji.bikeback.global.monitor.MonitoringService;
import com.bikeprojectminji.bikeback.global.monitor.MonitoringStatusResponse;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitoringService monitoringService;

    @Test
    @DisplayName("health endpoint는 200과 최소 상태 필드를 반환한다")
    void healthReturnsOkResponse() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.data.service").value("bike-back"));
    }

    @Test
    @DisplayName("Prometheus metric endpoint는 공개 smoke 경로가 아니므로 인증 없이는 401을 반환한다")
    void prometheusEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("로그인 정보가 필요합니다."));
    }

    @Test
    @DisplayName("ready endpoint는 DB와 Redis가 준비되면 200과 correlation header를 반환한다")
    void readyReturnsOkWithCorrelationHeaders() throws Exception {
        given(monitoringService.getStatus()).willReturn(readinessStatus("ok", "ok"));

        mockMvc.perform(get("/ready")
                        .header("X-Request-Id", "ready-request-1")
                        .header("X-Trace-Id", "ready-trace-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "ready-request-1"))
                .andExpect(header().string("X-Trace-Id", "ready-trace-1"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ready"))
                .andExpect(jsonPath("$.data.service").value("bike-back"));
    }

    @Test
    @DisplayName("ready endpoint는 DB 또는 Redis가 준비되지 않으면 상세를 숨긴 503을 반환한다")
    void readyReturnsServiceUnavailableWhenDependencyIsDown() throws Exception {
        given(monitoringService.getStatus()).willReturn(readinessStatus("ok", "fail"));

        mockMvc.perform(get("/ready")
                        .header("X-Request-Id", "ready-request-2")
                        .header("X-Trace-Id", "ready-trace-2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Request-Id", "ready-request-2"))
                .andExpect(header().string("X-Trace-Id", "ready-trace-2"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("서비스가 아직 요청을 받을 준비가 되지 않았습니다."))
                .andExpect(jsonPath("$.data.status").value("not_ready"))
                .andExpect(jsonPath("$.data.service").value("bike-back"))
                .andExpect(jsonPath("$.data.database").doesNotExist())
                .andExpect(jsonPath("$.data.redis").doesNotExist());
    }

    private MonitoringStatusResponse readinessStatus(String databaseStatus, String redisStatus) {
        return new MonitoringStatusResponse(
                "bike-back",
                "ok".equals(databaseStatus) && "ok".equals(redisStatus) ? "ok" : "degraded",
                OffsetDateTime.parse("2026-07-16T12:00:00+09:00"),
                new DependencyStatusResponse(databaseStatus, "database detail"),
                new DependencyStatusResponse(redisStatus, "redis detail")
        );
    }
}
