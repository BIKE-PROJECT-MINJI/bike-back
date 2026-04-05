package com.bikeprojectminji.bikeback.profile.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.profile.dto.ProfileMeResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.profile.service.ProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProfileController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profileService;

    @Test
    @DisplayName("내 프로필 조회 API는 success 래퍼로 응답한다")
    void getMyProfileReturnsWrappedResponse() throws Exception {
        given(profileService.getMyProfile("1"))
                .willReturn(new ProfileMeResponse(1L, "bikeoasis@example.com", "bikeoasis", "https://example.com/me.png"));

        mockMvc.perform(get("/api/v1/profile/me").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.email").value("bikeoasis@example.com"))
                .andExpect(jsonPath("$.data.displayName").value("bikeoasis"));
    }

    @Test
    @DisplayName("내 프로필 수정 API는 success 래퍼로 응답한다")
    void updateMyProfileReturnsWrappedResponse() throws Exception {
        given(profileService.updateMyProfile("1", new com.bikeprojectminji.bikeback.profile.dto.UpdateProfileRequest("bikeoasis", "https://example.com/me.png")))
                .willReturn(new ProfileMeResponse(1L, "bikeoasis@example.com", "bikeoasis", "https://example.com/me.png"));

        mockMvc.perform(patch("/api/v1/profile/me")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "bikeoasis",
                                  "profileImageUrl": "https://example.com/me.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value(1));
    }

    @Test
    @DisplayName("내 프로필 API는 비로그인 요청이면 401을 반환한다")
    void getMyProfileReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/profile/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
