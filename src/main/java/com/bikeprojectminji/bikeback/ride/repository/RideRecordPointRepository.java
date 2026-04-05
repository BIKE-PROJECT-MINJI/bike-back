package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRecordPointRepository extends JpaRepository<RideRecordPointEntity, Long> {

    List<RideRecordPointEntity> findByRideRecordIdOrderByPointOrderAsc(Long rideRecordId);
}
