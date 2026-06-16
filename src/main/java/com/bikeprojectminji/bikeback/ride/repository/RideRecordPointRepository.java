package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RideRecordPointRepository extends JpaRepository<RideRecordPointEntity, Long> {

    List<RideRecordPointEntity> findByRideRecordIdOrderByPointOrderAsc(Long rideRecordId);

    long countByRideRecordId(Long rideRecordId);

    Optional<RideRecordPointEntity> findTopByRideRecordIdOrderByPointOrderDesc(Long rideRecordId);

    @Modifying(flushAutomatically = true)
    @Query("delete from RideRecordPointEntity p where p.rideRecordId in :rideRecordIds")
    void deleteByRideRecordIdIn(@Param("rideRecordIds") List<Long> rideRecordIds);
}
