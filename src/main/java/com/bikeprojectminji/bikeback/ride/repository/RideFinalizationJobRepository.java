package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideFinalizationJobEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideFinalizationJobStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RideFinalizationJobRepository extends JpaRepository<RideFinalizationJobEntity, Long> {

    Optional<RideFinalizationJobEntity> findByRideRecordId(Long rideRecordId);

    long countByStatus(RideFinalizationJobStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from RideFinalizationJobEntity j
            where j.status in :statuses
              and j.nextRunAt <= :now
            order by j.nextRunAt asc, j.id asc
            """)
    List<RideFinalizationJobEntity> findRunnableJobs(
            Collection<RideFinalizationJobStatus> statuses,
            OffsetDateTime now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select j from RideFinalizationJobEntity j
            where j.status = com.bikeprojectminji.bikeback.ride.entity.RideFinalizationJobStatus.RUNNING
              and j.lockedUntil < :now
            order by j.lockedUntil asc, j.id asc
            """)
    List<RideFinalizationJobEntity> findExpiredRunningJobs(OffsetDateTime now, Pageable pageable);
}
