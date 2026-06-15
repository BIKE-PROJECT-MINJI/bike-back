package com.bikeprojectminji.bikeback.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    @DisplayName("access token의 roles claim은 Spring Security authority로 변환된다")
    void accessTokenRolesBecomeAuthorities() {
        JwtAuthenticationToken authentication = securityConfig.accessTokenAuthentication(jwt("access", List.of("OPS")));

        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_OPS");
    }

    @Test
    @DisplayName("refresh token은 API 인증용 access token으로 사용할 수 없다")
    void refreshTokenIsRejectedForResourceAccess() {
        Jwt refreshToken = jwt("refresh", List.of("OPS"));

        assertThatThrownBy(() -> securityConfig.accessTokenAuthentication(refreshToken))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("로컬 웹 콘솔 origin은 API CORS 허용 origin으로 등록된다")
    void localWebConsoleOriginIsCorsAllowed() {
        UrlBasedCorsConfigurationSource source = (UrlBasedCorsConfigurationSource) securityConfig.corsConfigurationSource(
                "http://127.0.0.1:8081,http://localhost:8081"
        );

        CorsConfiguration configuration = source.getCorsConfigurations().get("/**");

        assertThat(configuration.getAllowedOrigins())
                .containsExactly("http://127.0.0.1:8081", "http://localhost:8081");
        assertThat(configuration.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders()).contains("Authorization", "Content-Type");
    }

    private Jwt jwt(String tokenType, List<String> roles) {
        Instant now = Instant.parse("2026-06-08T00:00:00Z");
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .subject("1")
                .claim("tokenType", tokenType)
                .claim("roles", roles)
                .build();
    }
}
