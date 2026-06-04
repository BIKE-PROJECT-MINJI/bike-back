package com.bikeprojectminji.bikeback.profile.repository;

import com.bikeprojectminji.bikeback.profile.entity.UserPreferenceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreferenceEntity, Long> {

    Optional<UserPreferenceEntity> findByUserId(Long userId);
}
