package com.bikeprojectminji.bikeback.controller.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.dto.auth.AuthMeResponse;
import com.bikeprojectminji.bikeback.dto.auth.LoginRequest;
import com.bikeprojectminji.bikeback.dto.auth.LoginResponse;
import com.bikeprojectminji.bikeback.dto.auth.RegisterRequest;
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
        "auth.jwt.token-validity-sec=3600"
})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @Test
    @DisplayName("회원가입 API는 success 래퍼로 JWT 응답을 반환한다")
    void registerReturnsWrappedResponse() throws Exception {
        RegisterRequest request = new RegisterRequest("bikeoasis@example.com", "example-password", "bikeoasis", null, null);
        given(authService.register(request))
                .willReturn(new LoginResponse("Bearer", "jwt-token", 3600, 1L, "bikeoasis"));

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
                .andExpect(jsonPath("$.data.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("로그인 API는 success 래퍼로 JWT 응답을 반환한다")
    void loginReturnsWrappedResponse() throws Exception {
        LoginRequest request = new LoginRequest("bikeoasis@example.com", "example-password");
        given(authService.login(request))
                .willReturn(new LoginResponse("Bearer", "jwt-token", 3600, 1L, "bikeoasis"));

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
                .andExpect(jsonPath("$.data.accessToken").value("jwt-token"));
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
}
