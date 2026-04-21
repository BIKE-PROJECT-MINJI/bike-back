package com.bikeprojectminji.bikeback.ride.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.ride.service.RideRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RideRecordController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class RideRecordControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RideRecordService rideRecordService;

    @Test
    @DisplayName("자유 주행 기록 저장 API는 인증된 사용자의 저장 결과를 응답한다")
    void saveRideRecordReturnsWrappedResponse() throws Exception {
        given(rideRecordService.saveRideRecord(org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.any()))
                .willReturn(new RideRecordResponse(1001L, 1L, 2, "FINALIZING"));

        mockMvc.perform(post("/api/v1/ride-records")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "startedAt": "2026-03-29T10:00:00+09:00",
                                  "endedAt": "2026-03-29T11:00:00+09:00",
                                  "summary": {
                                    "distanceM": 18250,
                                    "durationSec": 3600
                                  },
                                  "routePoints": [
                                    {
                                      "pointOrder": 1,
                                      "latitude": 37.5665,
                                      "longitude": 126.9780
                                    },
                                    {
                                      "pointOrder": 2,
                                      "latitude": 37.5671,
                                      "longitude": 126.9792
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rideRecordId").value(1001))
                .andExpect(jsonPath("$.data.ownerUserId").value(1))
                .andExpect(jsonPath("$.data.finalizationStatus").value("FINALIZING"));
    }

    @Test
    @DisplayName("자유 주행 기록 저장 API는 비로그인 요청이면 401을 반환한다")
    void saveRideRecordReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/ride-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
