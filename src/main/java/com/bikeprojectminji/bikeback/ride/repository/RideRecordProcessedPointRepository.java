package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRecordProcessedPointRepository extends JpaRepository<RideRecordProcessedPointEntity, Long> {

    List<RideRecordProcessedPointEntity> findByRideRecordIdOrderByPointOrderAsc(Long rideRecordId);

    void deleteByRideRecordId(Long rideRecordId);
}
