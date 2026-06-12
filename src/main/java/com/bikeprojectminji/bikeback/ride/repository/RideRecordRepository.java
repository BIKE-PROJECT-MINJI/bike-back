package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RideRecordRepository extends JpaRepository<RideRecordEntity, Long> {

    Optional<RideRecordEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RideRecordEntity r where r.id = :id")
    Optional<RideRecordEntity> findByIdForUpdate(Long id);

    Optional<RideRecordEntity> findByOwnerUserIdAndClientRideId(Long ownerUserId, String clientRideId);

    List<RideRecordEntity> findByOwnerUserId(Long ownerUserId);

    List<RideRecordEntity> findTop20ByOwnerUserIdOrderByEndedAtDescIdDesc(Long ownerUserId);

    Optional<RideRecordEntity> findFirstByOwnerUserIdAndFinalizationStatusOrderByEndedAtDescIdDesc(Long ownerUserId, String finalizationStatus);

    @Query("""
            select new com.bikeprojectminji.bikeback.ride.repository.RideRecordActivityAggregate(
                count(r),
                coalesce(sum(r.distanceM), 0),
                coalesce(sum(r.durationSec), 0),
                coalesce(sum(case when r.endedAt between :start and :end then 1 else 0 end), 0),
                coalesce(sum(case when r.endedAt between :start and :end then r.distanceM else 0 end), 0),
                coalesce(sum(case when r.endedAt between :start and :end then r.durationSec else 0 end), 0)
            )
            from RideRecordEntity r
            where r.ownerUserId = :ownerUserId
              and r.finalizationStatus = :finalizationStatus
            """)
    RideRecordActivityAggregate findActivityAggregateByOwnerUserIdAndFinalizationStatus(
            Long ownerUserId,
            String finalizationStatus,
            OffsetDateTime start,
            OffsetDateTime end
    );

}
