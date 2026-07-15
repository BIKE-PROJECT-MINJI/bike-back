package com.bikeprojectminji.bikeback.airoute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.InvalidRouteRequestException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
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
    @DisplayName("AI 경로 추천 REST는 비로그인 요청에 게스트 device id가 없으면 400을 반환한다")
    void planRequiresGuestDeviceIdWhenAnonymous() throws Exception {
        willThrow(new BadRequestException("게스트 device id가 필요합니다."))
                .given(aiRouteQuotaService)
                .checkGuestAllowed(null, "127.0.0.1");

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("텍스트 기반 AI 경로 추천 REST는 비로그인 요청에 게스트 device id가 없으면 400을 반환한다")
    void planFromTextRequiresGuestDeviceIdWhenAnonymous() throws Exception {
        willThrow(new BadRequestException("게스트 device id가 필요합니다."))
                .given(aiRouteQuotaService)
                .checkGuestAllowed(null, "127.0.0.1");

        mockMvc.perform(post("/api/v1/ai-routes/plan/from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lat": 37.4812,
                                  "lon": 126.9527,
                                  "text": "오르막이 많은 곳 추천"
                                }
                                """))
                .andExpect(status().isBadRequest());
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

        verify(aiRouteQuotaService).checkAuthenticatedAllowed("1");
    }

    @Test
    @DisplayName("AI 경로 추천 REST는 게스트 device id 기준으로 quota를 확인한다")
    void planChecksQuotaByGuestDeviceId() throws Exception {
        given(aiRoutePlannerService.plan(eq(null), any())).willReturn(new AiRoutePlanResponse(
                "plan-guest-1",
                "FALLBACK",
                "게스트 추천 경로",
                "LOW",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                70,
                null,
                null,
                List.of(),
                false
        ));

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .header("X-Guest-Device-Id", "guest-device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isOk());

        verify(aiRouteQuotaService).checkGuestAllowed("guest-device-1", "127.0.0.1");
        verify(aiRoutePlannerService).plan(eq(null), any());
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

        verify(aiRouteQuotaService).checkAuthenticatedAllowed("1");
        verify(aiRoutePlannerService).planFromText(eq("1"), any());
    }

    @Test
    @DisplayName("AI 경로 REST는 입력 오류를 400 INVALID_ROUTE_REQUEST로 구분한다")
    void planMapsInvalidRequestContract() throws Exception {
        given(aiRoutePlannerService.plan(eq("1"), any()))
                .willThrow(new InvalidRouteRequestException("현재 위치 lat가 유효하지 않습니다."));

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.data.errorCode").value("INVALID_ROUTE_REQUEST"));
    }

    @Test
    @DisplayName("AI 경로 REST는 후보 없음을 422 ROUTE_NOT_FOUND로 구분한다")
    void planMapsRouteNotFoundContract() throws Exception {
        given(aiRoutePlannerService.plan(eq("1"), any()))
                .willThrow(new RouteNotFoundException("조건을 충족하는 경로가 없습니다."));

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.data.errorCode").value("ROUTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("AI 경로 REST는 provider quota를 retryable 429로 구분한다")
    void planMapsRoutingQuotaContract() throws Exception {
        given(aiRoutePlannerService.plan(eq("1"), any()))
                .willThrow(new RetryableTooManyRequestsException(
                        "라우팅 요청 한도에 도달했습니다.", "ROUTING_QUOTA_EXCEEDED", 9));

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "9"))
                .andExpect(jsonPath("$.data.errorCode").value("ROUTING_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("AI 경로 REST는 모든 provider 장애를 retryable 503으로 구분한다")
    void planMapsRoutingProviderUnavailableContract() throws Exception {
        given(aiRoutePlannerService.plan(eq("1"), any()))
                .willThrow(new RoutingProviderUnavailableException("라우팅 provider가 일시적으로 불안정합니다.", 3));

        mockMvc.perform(post("/api/v1/ai-routes/plan")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.data.errorCode").value("ROUTING_PROVIDER_UNAVAILABLE"));
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
