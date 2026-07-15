package com.bikeprojectminji.bikeback.party.repository;

import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RidePartyRepository extends JpaRepository<RidePartyEntity, Long> {

    List<RidePartyEntity> findByCourseIdAndStatusOrderByCreatedAtDescIdDesc(Long courseId, RidePartyStatus status);

    List<RidePartyEntity> findByCourseIdAndStatusInOrderByCreatedAtDescIdDesc(
            Long courseId,
            List<RidePartyStatus> statuses
    );

    List<RidePartyEntity> findByStatusInOrderByCreatedAtDescIdDesc(
            List<RidePartyStatus> statuses,
            Pageable pageable
    );

    @Query("""
            select party
            from RidePartyEntity party, RidePartyMemberEntity member
            where member.partyId = party.id
              and member.userId = :userId
              and member.status = com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus.JOINED
              and party.status in :statuses
            order by party.createdAt desc, party.id desc
            """)
    List<RidePartyEntity> findJoinedByUserIdAndStatuses(
            @Param("userId") Long userId,
            @Param("statuses") List<RidePartyStatus> statuses,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select party from RidePartyEntity party where party.id = :partyId")
    Optional<RidePartyEntity> findByIdForUpdate(@Param("partyId") Long partyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select party from RidePartyEntity party where party.id = :partyId and party.status = :status")
    Optional<RidePartyEntity> findByIdAndStatusForUpdate(
            @Param("partyId") Long partyId,
            @Param("status") RidePartyStatus status
    );
}
