package com.bikeprojectminji.bikeback.party.controller;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.party.dto.CreateRidePartyRequest;
import com.bikeprojectminji.bikeback.party.dto.RidePartyListResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyMemberListResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyReportRequest;
import com.bikeprojectminji.bikeback.party.dto.RidePartyReportResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartySocketTokenResponse;
import com.bikeprojectminji.bikeback.party.entity.RidePartyReportReason;
import com.bikeprojectminji.bikeback.party.service.RidePartyReportService;
import com.bikeprojectminji.bikeback.party.service.RidePartyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/parties")
public class RidePartyController {

    private final RidePartyService ridePartyService;
    private final RidePartyReportService ridePartyReportService;

    public RidePartyController(RidePartyService ridePartyService, RidePartyReportService ridePartyReportService) {
        this.ridePartyService = ridePartyService;
        this.ridePartyReportService = ridePartyReportService;
    }

    @PostMapping
    public ApiResponse<RidePartyResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRidePartyRequest request) {
        return ApiResponse.success(ridePartyService.create(jwt.getSubject(), request));
    }

    @GetMapping
    public ApiResponse<RidePartyListResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long courseId,
            @RequestParam(defaultValue = "ALL") String scope
    ) {
        if (courseId != null) {
            return ApiResponse.success(ridePartyService.listByCourse(jwt.getSubject(), courseId));
        }
        if ("MINE".equalsIgnoreCase(scope)) {
            return ApiResponse.success(ridePartyService.listMine(jwt.getSubject()));
        }
        if ("ALL".equalsIgnoreCase(scope)) {
            return ApiResponse.success(ridePartyService.listAll(jwt.getSubject()));
        }
        throw new BadRequestException("scope는 ALL 또는 MINE이어야 합니다.");
    }

    @PostMapping("/{partyId}/join")
    public ApiResponse<RidePartyResponse> join(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.join(jwt.getSubject(), partyId));
    }

    @PostMapping("/{partyId}/members")
    public ApiResponse<RidePartyResponse> joinMember(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.join(jwt.getSubject(), partyId));
    }

    @GetMapping("/{partyId}/members")
    public ApiResponse<RidePartyMemberListResponse> listMembers(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.listMembers(jwt.getSubject(), partyId));
    }

    @PostMapping("/{partyId}/socket-token")
    public ApiResponse<RidePartySocketTokenResponse> issueSocketToken(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.issueSocketToken(jwt.getSubject(), partyId));
    }

    @PostMapping("/{partyId}/reports")
    public ApiResponse<RidePartyReportResponse> report(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long partyId,
            @RequestBody RidePartyReportRequest request
    ) {
        return ApiResponse.success(ridePartyReportService.reportParty(jwt.getSubject(), partyId, parseReportReason(request)));
    }

    @PostMapping("/{partyId}/leave")
    public ApiResponse<RidePartyResponse> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.leave(jwt.getSubject(), partyId));
    }

    @PostMapping("/{partyId}/start")
    public ApiResponse<RidePartyResponse> start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.start(jwt.getSubject(), partyId));
    }

    private RidePartyReportReason parseReportReason(RidePartyReportRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new BadRequestException("reason은 비어 있을 수 없습니다.");
        }
        try {
            return RidePartyReportReason.valueOf(request.reason().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("reason은 지원하지 않는 신고 사유입니다.");
        }
    }
}
