package com.bikeprojectminji.bikeback.achievement.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.achievement.dto.AchievementItemResponse;
import com.bikeprojectminji.bikeback.achievement.dto.AchievementListResponse;
import com.bikeprojectminji.bikeback.achievement.entity.AchievementType;
import com.bikeprojectminji.bikeback.achievement.service.AchievementService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AchievementController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class AchievementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AchievementService achievementService;

    @Test
    @DisplayName("내 성취 목록 API는 current path에서 success 래퍼로 응답한다")
    void getMyAchievementsReturnsWrappedResponse() throws Exception {
        given(achievementService.getMyAchievements("1"))
                .willReturn(new AchievementListResponse(List.of(new AchievementItemResponse(
                        AchievementType.FIRST_COURSE_COMPLETION,
                        "첫 코스 완주",
                        "첫 코스를 완주했습니다.",
                        "global",
                        10L,
                        20L,
                        OffsetDateTime.parse("2026-06-04T06:00:00Z")
                ))));

        mockMvc.perform(get("/api/v1/me/achievements").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.achievements[0].type").value("FIRST_COURSE_COMPLETION"))
                .andExpect(jsonPath("$.data.achievements[0].title").value("첫 코스 완주"));
    }

    @Test
    @DisplayName("내 성취 목록 API는 비로그인 요청이면 401을 반환한다")
    void getMyAchievementsReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/me/achievements"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
