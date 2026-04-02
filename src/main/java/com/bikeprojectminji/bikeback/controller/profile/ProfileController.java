package com.bikeprojectminji.bikeback.controller.profile;

import com.bikeprojectminji.bikeback.dto.profile.ProfileMeResponse;
import com.bikeprojectminji.bikeback.dto.profile.UpdateProfileRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.service.profile.ProfileService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public ApiResponse<ProfileMeResponse> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(profileService.getMyProfile(jwt.getSubject()));
    }

    @PatchMapping("/me")
    public ApiResponse<ProfileMeResponse> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateProfileRequest request
    ) {
        validateUpdateRequest(request);
        return ApiResponse.success(profileService.updateMyProfile(jwt.getSubject(), request));
    }

    private void validateUpdateRequest(UpdateProfileRequest request) {
        if (request == null) {
            throw new BadRequestException("프로필 수정 요청 본문이 필요합니다.");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new BadRequestException("displayName은 비어 있을 수 없습니다.");
        }
    }
}
