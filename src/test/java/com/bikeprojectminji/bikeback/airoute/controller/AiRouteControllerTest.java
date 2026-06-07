package com.bikeprojectminji.bikeback.airoute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AiRouteController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AiRouteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiRoutePlannerService aiRoutePlannerService;

    @MockitoBean
    private AiRouteQuotaService aiRouteQuotaService;

    @Test
    @DisplayName("AI 경로 추천 REST는 access token이 없으면 거부한다")
    void planRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AI 경로 추천 REST는 인증 subject 기준으로 quota를 확인한다")
    void planChecksQuotaByAuthenticatedSubject() throws Exception {
        given(aiRoutePlannerService.plan(eq("1"), any())).willReturn(new AiRoutePlanResponse(
                "plan-1",
                "FALLBACK",
                "추천 경로",
                "LOW",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                80,
                null,
                null,
                List.of(),
                false
        ));

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk());

        verify(aiRouteQuotaService).checkAllowed("1");
    }

    @Test
    @DisplayName("텍스트 기반 AI 경로 추천 REST는 인증 subject 기준으로 quota를 확인한다")
    void planFromTextChecksQuotaByAuthenticatedSubject() throws Exception {
        given(aiRoutePlannerService.planFromText(eq("1"), any())).willReturn(new AiRoutePlanResponse(
                "plan-text-1",
                "READY",
                "오르막 추천 경로",
                "MEDIUM",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                82,
                null,
                null,
                List.of(),
                false
        ));

        mockMvc.perform(post("/api/v1/ai-routes/plan/from-text")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lat": 37.4812,
                                  "lon": 126.9527,
                                  "text": "오르막이 많은 곳 추천"
                                }
                                """))
                .andExpect(status().isOk());

        verify(aiRouteQuotaService).checkAllowed("1");
        verify(aiRoutePlannerService).planFromText(eq("1"), any());
    }

    private String validRequestJson() {
        return """
                {
                  "lat": 37.5665,
                  "lon": 126.9780,
                  "destinationLat": 37.5796,
                  "destinationLon": 126.9770,
                  "destinationLabel": "북악스카이웨이",
                  "rideStyle": "SCENERY_FIRST"
                }
                """;
    }
}
