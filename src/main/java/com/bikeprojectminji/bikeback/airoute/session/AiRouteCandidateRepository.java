package com.bikeprojectminji.bikeback.airoute.session;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiRouteCandidateRepository extends JpaRepository<AiRouteCandidateEntity, Long> {

    List<AiRouteCandidateEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    Optional<AiRouteCandidateEntity> findByIdAndSessionId(Long id, Long sessionId);
}
