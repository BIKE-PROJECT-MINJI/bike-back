package com.bikeprojectminji.bikeback.party.repository;

import com.bikeprojectminji.bikeback.party.entity.RidePartyLocationPointEntity;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RidePartyLocationPointRepository extends JpaRepository<RidePartyLocationPointEntity, Long> {

    int deleteByCreatedAtBefore(OffsetDateTime threshold);
}
