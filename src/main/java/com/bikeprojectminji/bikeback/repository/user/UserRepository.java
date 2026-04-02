package com.bikeprojectminji.bikeback.repository.user;

import com.bikeprojectminji.bikeback.entity.user.UserEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByExternalId(String externalId);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
