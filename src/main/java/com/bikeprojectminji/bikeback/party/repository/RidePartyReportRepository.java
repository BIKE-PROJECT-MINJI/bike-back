package com.bikeprojectminji.bikeback.party.repository;

import com.bikeprojectminji.bikeback.party.entity.RidePartyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RidePartyReportRepository extends JpaRepository<RidePartyReportEntity, Long> {

    boolean existsByPartyIdAndReporterUserId(Long partyId, Long reporterUserId);

    long countByPartyId(Long partyId);
}
