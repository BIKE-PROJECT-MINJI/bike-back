package com.bikeprojectminji.bikeback.controller.course;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyGateResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.service.course.CourseService;
import com.bikeprojectminji.bikeback.service.ridepolicy.RidePolicyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CourseController.class)
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
    private CourseService courseService;

    @MockitoBean
    private RidePolicyService ridePolicyService;

    @Test
    @DisplayName("주행 정책 API는 정책 판단 결과를 success 래퍼로 응답한다")
    void evaluateRidePolicyReturnsWrappedResponse() throws Exception {
        RidePolicyEvaluationResponse response = new RidePolicyEvaluationResponse(
                "PRE_START",
                new RidePolicyGateResponse("UNDETERMINED", "LOCATION_LOW_ACCURACY"),
                new RidePolicyGateResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                "PRE_START_UNDETERMINED",
                "위치 정확도가 낮아 시작 가능 여부를 판단하기 어렵습니다."
        );
        given(ridePolicyService.evaluate(any(), any())).willReturn(response);

        mockMvc.perform(post("/api/v1/courses/1/ride-policy/evaluate")
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
                .andExpect(jsonPath("$.data.startGate.status").value("UNDETERMINED"))
                .andExpect(jsonPath("$.data.offRoute.reasonCode").value("NOT_ACTIVE_YET"))
                .andExpect(jsonPath("$.data.overallState").value("PRE_START_UNDETERMINED"));
    }
}
