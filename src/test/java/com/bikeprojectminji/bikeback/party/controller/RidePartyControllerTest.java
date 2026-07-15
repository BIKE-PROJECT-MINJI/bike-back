package com.bikeprojectminji.bikeback.party.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.party.dto.RidePartyListResponse;
import com.bikeprojectminji.bikeback.party.service.RidePartyReportService;
import com.bikeprojectminji.bikeback.party.service.RidePartyService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RidePartyController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class RidePartyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RidePartyService ridePartyService;

    @MockitoBean
    private RidePartyReportService ridePartyReportService;

    @Test
    @DisplayName("내 파티 목록은 MINE scope를 인증 사용자 기준으로 조회한다")
    void listsCurrentUserPartiesByScope() throws Exception {
        given(ridePartyService.listMine("2")).willReturn(new RidePartyListResponse(List.of()));

        mockMvc.perform(get("/api/v1/parties")
                        .queryParam("scope", "MINE")
                        .with(jwt().jwt(jwt -> jwt.subject("2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());

        verify(ridePartyService).listMine("2");
    }

    @Test
    @DisplayName("파티 목록의 알 수 없는 scope는 400으로 거부한다")
    void rejectsUnknownPartyListScope() throws Exception {
        mockMvc.perform(get("/api/v1/parties")
                        .queryParam("scope", "UNKNOWN")
                        .with(jwt().jwt(jwt -> jwt.subject("2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("파티 목록은 인증이 없으면 401을 반환한다")
    void rejectsPartyListWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/parties").queryParam("scope", "ALL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
