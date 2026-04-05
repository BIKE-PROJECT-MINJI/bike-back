package com.bikeprojectminji.bikeback.location.controller;

import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.location.dto.RecentLocationResponse;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/location")
public class LocationController {

    private final RecentLocationCacheService recentLocationCacheService;

    public LocationController(RecentLocationCacheService recentLocationCacheService) {
        this.recentLocationCacheService = recentLocationCacheService;
    }

    @GetMapping("/me/recent")
    public ApiResponse<RecentLocationResponse> getMyRecentLocation(@AuthenticationPrincipal Jwt jwt) {
        return recentLocationCacheService.find(jwt.getSubject())
                .map(snapshot -> ApiResponse.success(new RecentLocationResponse(
                        snapshot.rideRecordId(),
                        snapshot.latitude(),
                        snapshot.longitude(),
                        snapshot.pointOrder(),
                        snapshot.status(),
                        snapshot.capturedAt()
                )))
                .orElseThrow(() -> new NotFoundException("최근 위치 정보를 찾을 수 없습니다."));
    }
}
