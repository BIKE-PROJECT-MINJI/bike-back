package com.bikeprojectminji.bikeback.location.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import com.bikeprojectminji.bikeback.location.service.RecentLocationSnapshot;
import com.bikeprojectminji.bikeback.location.service.RecentLocationStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LocationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class LocationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecentLocationCacheService recentLocationCacheService;

    @Test
    @DisplayName("최근 위치 조회 API는 인증 사용자의 최근 위치를 success 래퍼로 응답한다")
    void getMyRecentLocationReturnsWrappedResponse() throws Exception {
        given(recentLocationCacheService.find("1")).willReturn(Optional.of(new RecentLocationSnapshot(
                1001L,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                22,
                RecentLocationStatus.COMPLETE,
                OffsetDateTime.parse("2026-04-05T10:00:00+09:00")
        )));

        mockMvc.perform(get("/api/v1/location/me/recent")
                        .with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rideRecordId").value(1001))
                .andExpect(jsonPath("$.data.pointOrder").value(22))
                .andExpect(jsonPath("$.data.status").value("COMPLETE"));
    }

    @Test
    @DisplayName("최근 위치 캐시가 없으면 404를 응답한다")
    void getMyRecentLocationReturnsNotFoundWhenCacheMissing() throws Exception {
        given(recentLocationCacheService.find("1")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/location/me/recent")
                        .with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("최근 위치 정보를 찾을 수 없습니다."));
    }
}
