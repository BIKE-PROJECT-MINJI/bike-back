package com.bikeprojectminji.bikeback.profile.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.profile.dto.ProfileActivitySummaryResponse;
import com.bikeprojectminji.bikeback.profile.dto.ProfileOverallActivitySummaryResponse;
import com.bikeprojectminji.bikeback.profile.dto.ProfileMeResponse;
import com.bikeprojectminji.bikeback.profile.dto.ProfileWeeklyActivitySummaryResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.profile.service.ProfileService;
import java.math.BigDecimal;
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

    @Test
    @DisplayName("내 활동 요약 조회 API는 주간/전체 요약을 success 래퍼로 응답한다")
    void getMyActivitySummaryReturnsWrappedResponse() throws Exception {
        given(profileService.getMyActivitySummary("1"))
                .willReturn(new ProfileActivitySummaryResponse(
                        new ProfileWeeklyActivitySummaryResponse(new BigDecimal("24.5"), 2, 70, 1),
                        new ProfileOverallActivitySummaryResponse(new BigDecimal("120.5"), 12, new BigDecimal("18.1"), 0)
                ));

        mockMvc.perform(get("/api/v1/profile/me/activity-summary").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.weeklySummary.distanceKm").value(24.5))
                .andExpect(jsonPath("$.data.weeklySummary.rideCount").value(2))
                .andExpect(jsonPath("$.data.weeklySummary.durationMinutes").value(70))
                .andExpect(jsonPath("$.data.weeklySummary.savedCourseCount").value(1))
                .andExpect(jsonPath("$.data.overallSummary.totalDistanceKm").value(120.5))
                .andExpect(jsonPath("$.data.overallSummary.totalRides").value(12))
                .andExpect(jsonPath("$.data.overallSummary.avgSpeedKmh").value(18.1))
                .andExpect(jsonPath("$.data.overallSummary.totalElevationM").value(0));
    }
}
