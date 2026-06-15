package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionCreateRequest;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.PromoteAiRouteCandidateRequest;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-route-sessions")
public class AiRouteGenerationSessionController {

    private final AiRouteGenerationSessionService sessionService;

    public AiRouteGenerationSessionController(AiRouteGenerationSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ApiResponse<AiRouteGenerationSessionResponse> createSession(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody AiRouteGenerationSessionCreateRequest request
    ) {
        return ApiResponse.success(sessionService.createSession(jwt.getSubject(), request));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<AiRouteGenerationSessionResponse> getSession(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId
    ) {
        return ApiResponse.success(sessionService.getSession(jwt.getSubject(), sessionId));
    }

    @PostMapping("/{sessionId}/candidates/{candidateId}/course")
    public ApiResponse<AiRoutePromotedCourseResponse> promoteCandidate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long sessionId,
            @PathVariable Long candidateId,
            @RequestBody PromoteAiRouteCandidateRequest request
    ) {
        return ApiResponse.success(sessionService.promoteCandidate(jwt.getSubject(), sessionId, candidateId, request));
    }
}
