package com.bikeprojectminji.bikeback.ride.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyGateResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.ride.policy.service.RidePolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RidePolicyController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class RidePolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RidePolicyService ridePolicyService;

    @Test
    @DisplayName("주행 정책 평가는 기존 course path 계약을 유지한 채 success 래퍼로 응답한다")
    void evaluateRidePolicyReturnsWrappedResponse() throws Exception {
        given(ridePolicyService.evaluate(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .willReturn(new RidePolicyEvaluationResponse(
                        "PRE_START",
                        new RidePolicyGateResponse("ELIGIBLE", "WITHIN_START_OR_ROUTE"),
                        new RidePolicyGateResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                        "PRE_START_ELIGIBLE",
                        "주행을 시작할 수 있습니다."
                ));

        mockMvc.perform(post("/api/v1/courses/7/ride-policy/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phase": "PRE_START",
                                  "location": {
                                    "lat": 37.5665,
                                    "lon": 126.9780,
                                    "accuracyM": 18.5,
                                    "capturedAt": "2026-03-29T10:15:19+09:00"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.phase").value("PRE_START"))
                .andExpect(jsonPath("$.data.startGate.status").value("ELIGIBLE"));
    }
}
