package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RideRecordRepository extends JpaRepository<RideRecordEntity, Long> {

    Optional<RideRecordEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    List<RideRecordEntity> findTop20ByOwnerUserIdOrderByEndedAtDescIdDesc(Long ownerUserId);

    @Query("select count(r) from RideRecordEntity r where r.ownerUserId = :ownerUserId and r.finalizationStatus = :finalizationStatus")
    long countByOwnerUserIdAndFinalizationStatus(Long ownerUserId, String finalizationStatus);

    @Query("select count(r) from RideRecordEntity r where r.ownerUserId = :ownerUserId and r.finalizationStatus = :finalizationStatus and r.endedAt between :start and :end")
    long countByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
            Long ownerUserId,
            String finalizationStatus,
            OffsetDateTime start,
            OffsetDateTime end
    );

    @Query("select coalesce(sum(r.distanceM), 0) from RideRecordEntity r where r.ownerUserId = :ownerUserId and r.finalizationStatus = :finalizationStatus")
    Long sumDistanceMByOwnerUserIdAndFinalizationStatus(Long ownerUserId, String finalizationStatus);

    @Query("select coalesce(sum(r.distanceM), 0) from RideRecordEntity r where r.ownerUserId = :ownerUserId and r.finalizationStatus = :finalizationStatus and r.endedAt between :start and :end")
    Long sumDistanceMByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
            Long ownerUserId,
            String finalizationStatus,
            OffsetDateTime start,
            OffsetDateTime end
    );

    @Query("select coalesce(sum(r.durationSec), 0) from RideRecordEntity r where r.ownerUserId = :ownerUserId and r.finalizationStatus = :finalizationStatus")
    Long sumDurationSecByOwnerUserIdAndFinalizationStatus(Long ownerUserId, String finalizationStatus);

    @Query("select coalesce(sum(r.durationSec), 0) from RideRecordEntity r where r.ownerUserId = :ownerUserId and r.finalizationStatus = :finalizationStatus and r.endedAt between :start and :end")
    Long sumDurationSecByOwnerUserIdAndFinalizationStatusAndEndedAtBetween(
            Long ownerUserId,
            String finalizationStatus,
            OffsetDateTime start,
            OffsetDateTime end
    );
}
