package com.bikeprojectminji.bikeback.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.profile.dto.UpdatePreferenceRequest;
import com.bikeprojectminji.bikeback.profile.dto.UserPreferenceResponse;
import com.bikeprojectminji.bikeback.profile.entity.BikeRoadPriority;
import com.bikeprojectminji.bikeback.profile.repository.UserPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ProfilePreferenceIntegrationTest {

    @Autowired
    private ProfileService profileService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository userPreferenceRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        userPreferenceRepository.deleteAll();
        userRepository.deleteAll();
        UserEntity user = userRepository.save(new UserEntity(
                "profile-preference-user",
                "preference@example.com",
                null,
                "preference-rider",
                null
        ));
        userId = user.getId();
    }

    @Test
    @DisplayName("선호경로 저장은 사용자별 설정을 생성하고 다시 조회한다")
    void updateMyPreferenceCreatesAndReadsPreference() {
        UserPreferenceResponse saved = profileService.updateMyPreference(
                String.valueOf(userId),
                new UpdatePreferenceRequest(true, BikeRoadPriority.HIGH, true, true)
        );

        UserPreferenceResponse found = profileService.getMyPreference(String.valueOf(userId));

        assertThat(saved.scenic()).isTrue();
        assertThat(saved.bikeRoadPriority()).isEqualTo(BikeRoadPriority.HIGH);
        assertThat(found).isEqualTo(saved);
    }

    @Test
    @DisplayName("선호경로 저장은 기존 사용자 설정을 갱신한다")
    void updateMyPreferenceUpdatesExistingPreference() {
        profileService.updateMyPreference(
                String.valueOf(userId),
                new UpdatePreferenceRequest(false, BikeRoadPriority.LOW, false, false)
        );

        UserPreferenceResponse updated = profileService.updateMyPreference(
                String.valueOf(userId),
                new UpdatePreferenceRequest(true, BikeRoadPriority.HIGH, true, true)
        );

        assertThat(userPreferenceRepository.findAll()).hasSize(1);
        assertThat(updated.scenic()).isTrue();
        assertThat(updated.bikeRoadPriority()).isEqualTo(BikeRoadPriority.HIGH);
        assertThat(updated.avoidDust()).isTrue();
        assertThat(updated.avoidUnsafeSurface()).isTrue();
    }
}
