package com.bikeprojectminji.bikeback.beta.controller;

import com.bikeprojectminji.bikeback.beta.dto.BetaInvitationVerifyRequest;
import com.bikeprojectminji.bikeback.beta.dto.BetaInvitationVerifyResponse;
import com.bikeprojectminji.bikeback.beta.service.BetaInvitationService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/beta-invitations")
public class BetaInvitationController {

    private final BetaInvitationService betaInvitationService;

    public BetaInvitationController(BetaInvitationService betaInvitationService) {
        this.betaInvitationService = betaInvitationService;
    }

    @PostMapping("/verify")
    public ApiResponse<BetaInvitationVerifyResponse> verify(@RequestBody BetaInvitationVerifyRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw new BadRequestException("초대 코드는 비어 있을 수 없습니다.");
        }
        return ApiResponse.success(betaInvitationService.verify(request.code()));
    }
}
