package com.bikeprojectminji.bikeback.profile.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.profile.dto.ProfileMeResponse;
import com.bikeprojectminji.bikeback.profile.dto.UpdateProfileRequest;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    // profile은 별도 aggregate를 만들지 않고,
    // auth 도메인이 소유한 사용자 계정 aggregate를 use case 단위로 조작한다.

    private final AuthService authService;
    private final UserRepository userRepository;

    public ProfileService(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    public ProfileMeResponse getMyProfile(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        return toResponse(user);
    }

    public ProfileMeResponse updateMyProfile(String subject, UpdateProfileRequest request) {
        UserEntity user = authService.findUserBySubject(subject);
        user.updateProfile(request.displayName(), request.profileImageUrl());
        UserEntity savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    private ProfileMeResponse toResponse(UserEntity user) {
        return new ProfileMeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getProfileImageUrl());
    }
}
