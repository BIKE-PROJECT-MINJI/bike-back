package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRecordPointRepository extends JpaRepository<RideRecordPointEntity, Long> {

    List<RideRecordPointEntity> findByRideRecordIdOrderByPointOrderAsc(Long rideRecordId);

    long countByRideRecordId(Long rideRecordId);

    Optional<RideRecordPointEntity> findTopByRideRecordIdOrderByPointOrderDesc(Long rideRecordId);

    void deleteByRideRecordIdIn(List<Long> rideRecordIds);
}
