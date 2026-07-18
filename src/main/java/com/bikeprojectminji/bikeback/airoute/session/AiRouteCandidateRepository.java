package com.bikeprojectminji.bikeback.airoute.session;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiRouteCandidateRepository extends JpaRepository<AiRouteCandidateEntity, Long> {

    List<AiRouteCandidateEntity> findBySessionIdOrderByIdAsc(Long sessionId);

    Optional<AiRouteCandidateEntity> findByIdAndSessionId(Long id, Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select candidate from AiRouteCandidateEntity candidate "
            + "where candidate.id = :id and candidate.sessionId = :sessionId")
    Optional<AiRouteCandidateEntity> findForUpdateByIdAndSessionId(
            @Param("id") Long id,
            @Param("sessionId") Long sessionId
    );
}
