package com.bikeprojectminji.bikeback.global.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(AdminEndpointSecurityTest.AdminSecurityProbeController.class)
@Import({SecurityConfig.class, AdminEndpointSecurityTest.AdminSecurityProbeController.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=900"
})
class AdminEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("admin API 경로는 인증 없이는 접근할 수 없다")
    void adminApiRejectsAnonymousUser() throws Exception {
        mockMvc.perform(get("/api/v1/admin/security-probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("admin API 경로는 일반 USER 권한으로 접근할 수 없다")
    void adminApiRejectsRegularUserRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/security-probe")
                        .with(jwt().jwt(jwt -> jwt
                                        .subject("1")
                                        .claim("tokenType", "access")
                                        .claim("roles", List.of("USER")))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin API 경로는 OPS_ADMIN 권한으로 접근할 수 있다")
    void adminApiAllowsOpsAdminRole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/security-probe")
                        .with(jwt().jwt(jwt -> jwt
                                        .subject("2")
                                        .claim("tokenType", "access")
                                        .claim("roles", List.of("USER", "OPS_ADMIN")))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS_ADMIN"))))
                .andExpect(status().isOk());
    }

    @RestController
    public static class AdminSecurityProbeController {

        @GetMapping("/api/v1/admin/security-probe")
        String probe() {
            return "ok";
        }
    }
}
