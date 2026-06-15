package com.bikeprojectminji.bikeback.airoute.controller;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteTextPlanRequest;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-routes")
public class AiRouteController {

    private static final String GUEST_DEVICE_ID_HEADER = "X-Guest-Device-Id";

    private final AiRoutePlannerService aiRoutePlannerService;
    private final AiRouteQuotaService aiRouteQuotaService;

    public AiRouteController(AiRoutePlannerService aiRoutePlannerService, AiRouteQuotaService aiRouteQuotaService) {
        this.aiRoutePlannerService = aiRoutePlannerService;
        this.aiRouteQuotaService = aiRouteQuotaService;
    }

    @PostMapping("/plan")
    public ApiResponse<AiRoutePlanResponse> plan(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = GUEST_DEVICE_ID_HEADER, required = false) String guestDeviceId,
            @RequestBody AiRoutePlanRequest request,
            HttpServletRequest servletRequest
    ) {
        String subject = resolveQuotaAndPlannerSubject(jwt, guestDeviceId, servletRequest);
        return ApiResponse.success(aiRoutePlannerService.plan(subject, request));
    }

    @PostMapping("/plan/from-text")
    public ApiResponse<AiRoutePlanResponse> planFromText(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = GUEST_DEVICE_ID_HEADER, required = false) String guestDeviceId,
            @RequestBody AiRouteTextPlanRequest request,
            HttpServletRequest servletRequest
    ) {
        String subject = resolveQuotaAndPlannerSubject(jwt, guestDeviceId, servletRequest);
        return ApiResponse.success(aiRoutePlannerService.planFromText(subject, request));
    }

    private String resolveQuotaAndPlannerSubject(Jwt jwt, String guestDeviceId, HttpServletRequest servletRequest) {
        if (jwt != null) {
            aiRouteQuotaService.checkAuthenticatedAllowed(jwt.getSubject());
            return jwt.getSubject();
        }
        aiRouteQuotaService.checkGuestAllowed(guestDeviceId, servletRequest.getRemoteAddr());
        return null;
    }
}
