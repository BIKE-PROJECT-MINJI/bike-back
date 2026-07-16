package com.bikeprojectminji.bikeback.party.repository;

import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RidePartyMemberRepository extends JpaRepository<RidePartyMemberEntity, Long> {

    @Query(value = """
            select exists (
                select 1
                from ride_party_members member
                join ride_parties party on party.id = member.party_id
                join courses course on course.id = party.course_id
                where party.id = :partyId
                  and member.user_id = :userId
                  and member.status = :memberStatus
                  and party.status = :partyStatus
                  and course.visibility = :courseVisibility
                  and course.report_hidden = false
            )
            """, nativeQuery = true)
    boolean existsLocationShareAccess(
            @Param("partyId") Long partyId,
            @Param("userId") Long userId,
            @Param("memberStatus") String memberStatus,
            @Param("partyStatus") String partyStatus,
            @Param("courseVisibility") String courseVisibility
    );

    Optional<RidePartyMemberEntity> findByPartyIdAndUserId(Long partyId, Long userId);

    int countByPartyIdAndStatus(Long partyId, RidePartyMemberStatus status);

    List<RidePartyMemberEntity> findByPartyIdAndStatusOrderByJoinedAtAscIdAsc(Long partyId, RidePartyMemberStatus status);

    List<RidePartyMemberEntity> findByPartyIdInAndStatus(Collection<Long> partyIds, RidePartyMemberStatus status);
}
