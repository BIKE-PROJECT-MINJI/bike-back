package com.bikeprojectminji.bikeback.service.profile;

import com.bikeprojectminji.bikeback.dto.profile.ProfileMeResponse;
import com.bikeprojectminji.bikeback.dto.profile.UpdateProfileRequest;
import com.bikeprojectminji.bikeback.entity.user.UserEntity;
import com.bikeprojectminji.bikeback.repository.user.UserRepository;
import com.bikeprojectminji.bikeback.service.auth.AuthService;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

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
