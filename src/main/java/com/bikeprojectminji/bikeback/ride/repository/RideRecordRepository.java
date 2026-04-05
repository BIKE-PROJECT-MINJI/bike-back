package com.bikeprojectminji.bikeback.ride.repository;

import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRecordRepository extends JpaRepository<RideRecordEntity, Long> {

    Optional<RideRecordEntity> findByIdAndOwnerUserId(Long id, Long ownerUserId);
}
