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
        // profile 조회는 profile 도메인이 직접 사용자를 식별하지 않고,
        // auth 도메인을 통해 현재 사용자 aggregate를 받아 응답용 DTO로 변환한다.
        UserEntity user = authService.findUserBySubject(subject);
        return toResponse(user);
    }

    public ProfileMeResponse updateMyProfile(String subject, UpdateProfileRequest request) {
        // profile 수정은 displayName / profileImageUrl만 다루고,
        // 사용자 계정 aggregate 저장 책임은 auth가 소유한 UserRepository를 그대로 사용한다.
        UserEntity user = authService.findUserBySubject(subject);
        user.updateProfile(request.displayName(), request.profileImageUrl());
        UserEntity savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    private ProfileMeResponse toResponse(UserEntity user) {
        // 앱에서 필요한 최소 프로필 형태로만 잘라 응답해 도메인 entity가 직접 외부로 새지 않게 한다.
        return new ProfileMeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getProfileImageUrl());
    }
}
