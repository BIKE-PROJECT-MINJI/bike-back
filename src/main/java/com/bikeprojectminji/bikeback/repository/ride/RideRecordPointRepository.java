package com.bikeprojectminji.bikeback.repository.ride;

import com.bikeprojectminji.bikeback.entity.ride.RideRecordPointEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRecordPointRepository extends JpaRepository<RideRecordPointEntity, Long> {

    List<RideRecordPointEntity> findByRideRecordIdOrderByPointOrderAsc(Long rideRecordId);
}
