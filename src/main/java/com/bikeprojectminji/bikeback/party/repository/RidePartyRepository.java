package com.bikeprojectminji.bikeback.party.repository;

import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RidePartyRepository extends JpaRepository<RidePartyEntity, Long> {

    List<RidePartyEntity> findByCourseIdAndStatusOrderByCreatedAtDescIdDesc(Long courseId, RidePartyStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select party from RidePartyEntity party where party.id = :partyId and party.status = :status")
    Optional<RidePartyEntity> findByIdAndStatusForUpdate(
            @Param("partyId") Long partyId,
            @Param("status") RidePartyStatus status
    );
}
