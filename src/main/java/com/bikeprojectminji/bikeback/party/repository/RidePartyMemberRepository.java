package com.bikeprojectminji.bikeback.party.repository;

import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RidePartyMemberRepository extends JpaRepository<RidePartyMemberEntity, Long> {

    Optional<RidePartyMemberEntity> findByPartyIdAndUserId(Long partyId, Long userId);

    int countByPartyIdAndStatus(Long partyId, RidePartyMemberStatus status);

    List<RidePartyMemberEntity> findByPartyIdInAndStatus(Collection<Long> partyIds, RidePartyMemberStatus status);
}
