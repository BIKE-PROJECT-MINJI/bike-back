package com.bikeprojectminji.bikeback.profile.service;

import com.bikeprojectminji.bikeback.airoute.service.UserRoutePreferenceProvider;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.profile.entity.BikeRoadPriority;
import com.bikeprojectminji.bikeback.profile.repository.UserPreferenceRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ProfileRoutePreferenceProvider implements UserRoutePreferenceProvider {

    private final AuthService authService;
    private final UserPreferenceRepository userPreferenceRepository;

    public ProfileRoutePreferenceProvider(AuthService authService, UserPreferenceRepository userPreferenceRepository) {
        this.authService = authService;
        this.userPreferenceRepository = userPreferenceRepository;
    }

    @Override
    public Optional<String> findDefaultRideStyle(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        return userPreferenceRepository.findByUserId(user.getId())
                .map(preference -> toRideStyle(preference.isScenic(), preference.getBikeRoadPriority()));
    }

    private String toRideStyle(boolean scenic, BikeRoadPriority bikeRoadPriority) {
        if (scenic) {
            return "SCENERY_FIRST";
        }
        if (BikeRoadPriority.HIGH.equals(bikeRoadPriority)) {
            return "BIKE_PATH_FIRST";
        }
        return "BALANCED";
    }
}
