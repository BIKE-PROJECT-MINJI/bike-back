package com.bikeprojectminji.bikeback.auth.repository;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByExternalId(String externalId);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
