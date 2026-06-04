package com.bikeprojectminji.bikeback.auth.repository;

import com.bikeprojectminji.bikeback.auth.entity.UserConsentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserConsentRepository extends JpaRepository<UserConsentEntity, Long> {

    Optional<UserConsentEntity> findByUserId(Long userId);

    void deleteByUserId(Long userId);
}
