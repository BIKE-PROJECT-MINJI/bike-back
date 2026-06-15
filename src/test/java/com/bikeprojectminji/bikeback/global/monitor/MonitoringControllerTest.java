package com.bikeprojectminji.bikeback.global.monitor;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MonitoringController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class MonitoringControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitoringService monitoringService;

    @Test
    @DisplayName("모니터링 상태 API는 인증 없이는 의존성 상세를 노출하지 않는다")
    void getMonitoringStatusRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/health/monitor"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("모니터링 상태 API는 일반 access token으로는 의존성 상세를 노출하지 않는다")
    void getMonitoringStatusRejectsRegularUserToken() throws Exception {
        mockMvc.perform(get("/health/monitor")
                        .with(jwt().jwt(jwt -> accessToken(jwt, "regular-user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("모니터링 상태 API는 DB와 Redis 상태를 success 래퍼로 응답한다")
    void getMonitoringStatusReturnsWrappedResponse() throws Exception {
        given(monitoringService.getStatus()).willReturn(new MonitoringStatusResponse(
                "bike-back",
                "ok",
                OffsetDateTime.parse("2026-04-20T23:10:00+09:00"),
                new DependencyStatusResponse("ok", "select 1 success"),
                new DependencyStatusResponse("ok", "PONG")
        ));

        mockMvc.perform(get("/health/monitor")
                        .with(jwt().jwt(jwt -> opsAccessToken(jwt, "ops-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.service").value("bike-back"))
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.data.database.status").value("ok"))
                .andExpect(jsonPath("$.data.redis.status").value("ok"))
                .andExpect(jsonPath("$.data.redis.detail").value("PONG"));
    }

    @Test
    @DisplayName("모니터링 상태 API는 의존성 일부가 실패하면 degraded 상태를 그대로 응답한다")
    void getMonitoringStatusReturnsDegradedDependencyState() throws Exception {
        given(monitoringService.getStatus()).willReturn(new MonitoringStatusResponse(
                "bike-back",
                "degraded",
                OffsetDateTime.parse("2026-04-20T23:10:00+09:00"),
                new DependencyStatusResponse("fail", "database timeout"),
                new DependencyStatusResponse("ok", "PONG")
        ));

        mockMvc.perform(get("/health/monitor")
                        .with(jwt().jwt(jwt -> opsAccessToken(jwt, "ops-user"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("degraded"))
                .andExpect(jsonPath("$.data.database.status").value("fail"))
                .andExpect(jsonPath("$.data.database.detail").value("database timeout"));
    }

    private org.springframework.security.oauth2.jwt.Jwt.Builder accessToken(
            org.springframework.security.oauth2.jwt.Jwt.Builder jwt,
            String subject
    ) {
        return jwt.subject(subject).claim("tokenType", "access");
    }

    private org.springframework.security.oauth2.jwt.Jwt.Builder opsAccessToken(
            org.springframework.security.oauth2.jwt.Jwt.Builder jwt,
            String subject
    ) {
        return accessToken(jwt, subject).claim("roles", java.util.List.of("OPS"));
    }
}
