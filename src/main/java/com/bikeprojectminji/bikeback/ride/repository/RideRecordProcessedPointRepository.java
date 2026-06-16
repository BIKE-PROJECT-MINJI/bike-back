package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRecordProcessedPointRepository extends JpaRepository<RideRecordProcessedPointEntity, Long> {

    List<RideRecordProcessedPointEntity> findByRideRecordIdOrderByPointOrderAsc(Long rideRecordId);

    long countByRideRecordId(Long rideRecordId);

    void deleteByRideRecordId(Long rideRecordId);

    @Modifying(flushAutomatically = true)
    @Query("delete from RideRecordProcessedPointEntity p where p.rideRecordId in :rideRecordIds")
    void deleteByRideRecordIdIn(@Param("rideRecordIds") List<Long> rideRecordIds);
}
