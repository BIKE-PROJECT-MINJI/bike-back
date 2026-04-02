package com.bikeprojectminji.bikeback.repository.ride;

import com.bikeprojectminji.bikeback.entity.ride.RideRecordEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRecordRepository extends JpaRepository<RideRecordEntity, Long> {

    Optional<RideRecordEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
