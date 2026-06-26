package com.bikeprojectminji.bikeback.party.controller;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.party.dto.CreateRidePartyRequest;
import com.bikeprojectminji.bikeback.party.dto.RidePartyListResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyResponse;
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

    public RidePartyController(RidePartyService ridePartyService) {
        this.ridePartyService = ridePartyService;
    }

    @PostMapping
    public ApiResponse<RidePartyResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRidePartyRequest request) {
        return ApiResponse.success(ridePartyService.create(jwt.getSubject(), request));
    }

    @GetMapping
    public ApiResponse<RidePartyListResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam Long courseId) {
        return ApiResponse.success(ridePartyService.listByCourse(jwt.getSubject(), courseId));
    }

    @PostMapping("/{partyId}/join")
    public ApiResponse<RidePartyResponse> join(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.join(jwt.getSubject(), partyId));
    }

    @PostMapping("/{partyId}/leave")
    public ApiResponse<RidePartyResponse> leave(@AuthenticationPrincipal Jwt jwt, @PathVariable Long partyId) {
        return ApiResponse.success(ridePartyService.leave(jwt.getSubject(), partyId));
    }
}
