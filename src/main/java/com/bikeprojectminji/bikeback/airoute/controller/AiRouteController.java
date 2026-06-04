package com.bikeprojectminji.bikeback.airoute.controller;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.service.AiRouteQuotaService;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-routes")
public class AiRouteController {

    private final AiRoutePlannerService aiRoutePlannerService;
    private final AiRouteQuotaService aiRouteQuotaService;

    public AiRouteController(AiRoutePlannerService aiRoutePlannerService, AiRouteQuotaService aiRouteQuotaService) {
        this.aiRoutePlannerService = aiRoutePlannerService;
        this.aiRouteQuotaService = aiRouteQuotaService;
    }

    @PostMapping("/plan")
    public ApiResponse<AiRoutePlanResponse> plan(@AuthenticationPrincipal Jwt jwt, @RequestBody AiRoutePlanRequest request) {
        aiRouteQuotaService.checkAllowed(jwt.getSubject());
        return ApiResponse.success(aiRoutePlannerService.plan(jwt.getSubject(), request));
    }
}
