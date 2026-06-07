package com.bikeprojectminji.bikeback.auth.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.auth.dto.AuthMeResponse;
import com.bikeprojectminji.bikeback.auth.dto.KakaoLoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.dto.RefreshTokenRequest;
import com.bikeprojectminji.bikeback.auth.dto.RegisterRequest;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=900"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("회원가입 API는 success 래퍼로 JWT 응답을 반환한다")
    void registerReturnsWrappedResponse() throws Exception {
        RegisterRequest request = new RegisterRequest("bikeoasis@example.com", "example-password", "bikeoasis", null, null);
        given(authService.register(request))
                .willReturn(new LoginResponse("Bearer", "access-token", "refresh-token", 900, 1209600, 1L, "bikeoasis"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bikeoasis@example.com",
                                  "password": "example-password",
                                  "displayName": "bikeoasis",
                                  "profileImageUrl": null,
                                  "legacyExternalId": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.accessExpiresInSec").value(900))
                .andExpect(jsonPath("$.data.refreshExpiresInSec").value(1209600))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("회원가입 API는 요청 본문이 없으면 400을 반환한다")
    void registerReturnsBadRequestWithoutBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("요청 본문이 필요합니다."));
    }

    @Test
    @DisplayName("로그인 API는 success 래퍼로 JWT 응답을 반환한다")
    void loginReturnsWrappedResponse() throws Exception {
        LoginRequest request = new LoginRequest("bikeoasis@example.com", "example-password");
        given(authService.login(request))
                .willReturn(new LoginResponse("Bearer", "access-token", "refresh-token", 900, 1209600, 1L, "bikeoasis"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bikeoasis@example.com",
                                  "password": "example-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.accessExpiresInSec").value(900))
                .andExpect(jsonPath("$.data.refreshExpiresInSec").value(1209600));
    }

    @Test
    @DisplayName("카카오 로그인 API는 success 래퍼로 JWT 응답을 반환한다")
    void kakaoLoginReturnsWrappedResponse() throws Exception {
        KakaoLoginRequest request = new KakaoLoginRequest("kakao-access-token", "privacy-v1", "terms-v1", "location-v1", "2000-01-01");
        given(authService.kakaoLogin(request))
                .willReturn(new LoginResponse("Bearer", "access-token", "refresh-token", 900, 1209600, 1L, "gaja-rider"));

        mockMvc.perform(post("/api/v1/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kakaoAccessToken": "kakao-access-token",
                                  "privacyPolicyVersion": "privacy-v1",
                                  "termsVersion": "terms-v1",
                                  "locationTermsVersion": "location-v1",
                                  "birthDate": "2000-01-01"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.displayName").value("gaja-rider"));
    }

    @Test
    @DisplayName("카카오 로그인 API는 생년월일이 없으면 400을 반환한다")
    void kakaoLoginReturnsBadRequestWithoutBirthDate() throws Exception {
        mockMvc.perform(post("/api/v1/auth/kakao/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kakaoAccessToken": "kakao-access-token",
                                  "privacyPolicyVersion": "privacy-v1",
                                  "termsVersion": "terms-v1",
                                  "locationTermsVersion": "location-v1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("birthDate는 비어 있을 수 없습니다."));
    }

    @Test
    @DisplayName("리프레시 API는 success 래퍼로 새 access/refresh 토큰 응답을 반환한다")
    void refreshReturnsWrappedResponse() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("refresh-token");
        given(authService.refresh(request))
                .willReturn(new LoginResponse("Bearer", "new-access-token", "new-refresh-token", 900, 1209600, 1L, "bikeoasis"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "refresh-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.displayName").value("bikeoasis"));
    }

    @Test
    @DisplayName("리프레시 API는 잘못된 refresh token이면 401을 반환한다")
    void refreshReturnsUnauthorizedWhenTokenIsInvalid() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("broken-token");
        given(authService.refresh(request))
                .willThrow(new UnauthorizedException("로그인 정보가 필요합니다."));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "broken-token"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("로그인 정보가 필요합니다."));
    }

    @Test
    @DisplayName("내 인증 상태 API는 인증된 사용자의 정보를 반환한다")
    void getMeReturnsAuthenticatedUser() throws Exception {
        given(authService.getCurrentUser("1"))
                .willReturn(new AuthMeResponse(1L, "bikeoasis@example.com", "bikeoasis", true, "USER"));

        mockMvc.perform(get("/api/v1/auth/me").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.email").value("bikeoasis@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("bikeoasis"));
    }

    @Test
    @DisplayName("내 인증 상태 API는 비로그인 요청이면 401을 반환한다")
    void getMeReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("로그인 정보가 필요합니다."));
    }

    @Test
    @DisplayName("보호 API는 refresh token을 Bearer access token으로 인정하지 않는다")
    void getMeReturnsUnauthorizedWithRefreshToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("refresh", "bike-back-test")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT decoder는 refresh API 검증을 위해 refresh token도 해석할 수 있다")
    void jwtDecoderDecodesRefreshTokenForRefreshEndpointValidation() {
        org.springframework.security.oauth2.jwt.Jwt jwt = jwtDecoder.decode(token("refresh", "bike-back-test"));

        org.assertj.core.api.Assertions.assertThat(jwt.getClaimAsString("tokenType")).isEqualTo("refresh");
    }

    private String token(String tokenType, String issuer) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .subject("1")
                .claim("tokenType", tokenType)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims
        )).getTokenValue();
    }
}
