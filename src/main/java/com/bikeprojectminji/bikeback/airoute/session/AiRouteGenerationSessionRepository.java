package com.bikeprojectminji.bikeback.airoute.session;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiRouteGenerationSessionRepository extends JpaRepository<AiRouteGenerationSessionEntity, Long> {

    Optional<AiRouteGenerationSessionEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
