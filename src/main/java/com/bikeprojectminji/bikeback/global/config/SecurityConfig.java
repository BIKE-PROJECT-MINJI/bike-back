package com.bikeprojectminji.bikeback.global.config;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            @Value("${management.prometheus.public-scrape-enabled:false}") boolean prometheusPublicScrapeEnabled
    ) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    if (prometheusPublicScrapeEnabled) {
                        authorize.requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll();
                    } else {
                        authorize.requestMatchers(HttpMethod.GET, "/actuator/prometheus").hasAuthority("ROLE_OPS");
                    }
                    authorize
                        .requestMatchers("/api/v1/auth/register").permitAll()
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/auth/kakao/login").permitAll()
                        .requestMatchers("/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/beta-invitations/verify").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // /health는 로드밸런서/스모크 테스트용 생존 확인만 공개한다.
                        // DB/Redis 상세가 포함된 /health/monitor는 아래 OPS 권한 규칙에서 보호한다.
                        .requestMatchers("/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/weather/current").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai-routes/plan").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ai-routes/plan/from-text").permitAll()
                        .requestMatchers("/api/v1/ai-route-sessions/**").authenticated()
                        .requestMatchers("/ws/v1/ai-routes/**").authenticated()
                        .requestMatchers("/ws/v1/parties/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/featured").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/search").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/*/route-points").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courses/*/download").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courses/*/ride-policy/evaluate").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/addresses/search").permitAll()
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/logout", "/api/v1/profile/me", "/api/v1/profile/me/activity-summary").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/profile/me/preferences").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/profile/me/preferences").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/account/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/health/monitor").hasAuthority("ROLE_OPS")
                        .requestMatchers("/api/v1/admin/**").hasAuthority("ROLE_OPS_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/location/me/recent").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/me/achievements").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/events", "/api/v1/events/batch").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ride-records").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ride-records/summary").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ride-records/receipt").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ride-records").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ride-records/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ride-records/*/trace").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/ride-records/*/regenerate").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/ride-records/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courses").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courses/import-gpx").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courses/*/reports").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courses/*/share").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/courses/*").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/courses/*/visibility").authenticated()
                        .anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(this::accessTokenAuthentication)))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write(objectMapper.writeValueAsString(new ApiResponse<>(401, "로그인 정보가 필요합니다.", null)));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.getWriter().write(objectMapper.writeValueAsString(new ApiResponse<>(403, "이 리소스에 접근할 권한이 없습니다.", null)));
                        })
                );

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${auth.jwt.secret}") String secret,
            @Value("${auth.jwt.issuer}") String issuer
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuer);
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public JwtEncoder jwtEncoder(@Value("${auth.jwt.secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(secret)));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://127.0.0.1:8081,http://localhost:8081}") String allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(parseCsv(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Guest-Device-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private SecretKey secretKey(String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("AUTH_JWT_SECRET는 최소 32바이트 이상이어야 합니다.");
        }
        return new SecretKeySpec(secretBytes, "HmacSHA256");
    }

    JwtAuthenticationToken accessTokenAuthentication(Jwt jwt) {
        if (!"access".equals(jwt.getClaimAsString("tokenType"))) {
            throw new BadCredentialsException("access token이 필요합니다.");
        }
        return new JwtAuthenticationToken(jwt, authorities(jwt));
    }

    private List<GrantedAuthority> authorities(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return authorities;
        }
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String normalizedRole = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            authorities.add(new SimpleGrantedAuthority(normalizedRole));
        }
        return authorities;
    }

    private List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(","))
                .stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList();
    }
}
