package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordFinalizationFailureService {

    private final RideRecordRepository rideRecordRepository;

    public RideRecordFinalizationFailureService(RideRecordRepository rideRecordRepository) {
        this.rideRecordRepository = rideRecordRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long rideRecordId, String errorMessage) {
        RideRecordEntity rideRecord = rideRecordRepository.findByIdForUpdate(rideRecordId)
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        rideRecord.markFailed(OffsetDateTime.now(), errorMessage);
        rideRecordRepository.save(rideRecord);
    }
}
