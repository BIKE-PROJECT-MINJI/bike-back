package com.bikeprojectminji.bikeback.controller.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.dto.auth.AuthMeResponse;
import com.bikeprojectminji.bikeback.dto.auth.LoginResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.service.auth.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600",
        "auth.login.dev-enabled=true",
        "auth.login.dev-secret=dev-login-secret"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("로그인 API는 success 래퍼로 JWT 응답을 반환한다")
    void loginReturnsWrappedResponse() throws Exception {
        given(authService.login(new com.bikeprojectminji.bikeback.dto.auth.LoginRequest("device-1", "bikeoasis", null)))
                .willReturn(new LoginResponse("Bearer", "jwt-token", 3600, 1L, "bikeoasis"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Dev-Login-Secret", "dev-login-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "device-1",
                                  "displayName": "bikeoasis",
                                  "profileImageUrl": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("로그인 API는 dev secret이 없으면 403을 반환한다")
    void loginReturnsForbiddenWithoutDevSecret() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalId": "device-1",
                                  "displayName": "bikeoasis",
                                  "profileImageUrl": null
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("로그인 API 접근이 허용되지 않았습니다."));
    }

    @Test
    @DisplayName("내 인증 상태 API는 인증된 사용자의 정보를 반환한다")
    void getMeReturnsAuthenticatedUser() throws Exception {
        given(authService.getCurrentUser("1"))
                .willReturn(new AuthMeResponse(1L, "bikeoasis", true, "USER"));

        mockMvc.perform(get("/api/v1/auth/me").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(1))
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
}
