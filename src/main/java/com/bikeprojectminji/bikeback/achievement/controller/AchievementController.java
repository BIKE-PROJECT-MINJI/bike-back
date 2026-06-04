package com.bikeprojectminji.bikeback.achievement.controller;

import com.bikeprojectminji.bikeback.achievement.dto.AchievementListResponse;
import com.bikeprojectminji.bikeback.achievement.service.AchievementService;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/achievements")
public class AchievementController {

    private final AchievementService achievementService;

    public AchievementController(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @GetMapping
    public ApiResponse<AchievementListResponse> getMyAchievements(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(achievementService.getMyAchievements(jwt.getSubject()));
    }
}
