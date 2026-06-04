package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.OffsetDateTime;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordFinalizationService {

    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final RideRecordFinalizationProcessor rideRecordFinalizationProcessor;

    public RideRecordFinalizationService(
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            RideRecordFinalizationProcessor rideRecordFinalizationProcessor
    ) {
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.rideRecordFinalizationProcessor = rideRecordFinalizationProcessor;
    }

    @Async
    public void requestFinalization(Long rideRecordId) {
        rideRecordFinalizationProcessor.finalizeRideRecord(rideRecordId);
    }

    @Transactional
    public void markForRegeneration(RideRecordEntity rideRecord) {
        rideRecord.markFinalizing(OffsetDateTime.now());
        rideRecordRepository.save(rideRecord);
    }

    @Transactional(readOnly = true)
    public RideRecordFinalizationStatusResponse getStatus(RideRecordEntity rideRecord) {
        int rawPointCount = Math.toIntExact(rideRecordPointRepository.countByRideRecordId(rideRecord.getId()));
        int processedPointCount = Math.toIntExact(rideRecordProcessedPointRepository.countByRideRecordId(rideRecord.getId()));
        return new RideRecordFinalizationStatusResponse(
                rideRecord.getId(),
                rideRecord.getFinalizationStatus().name(),
                rawPointCount,
                processedPointCount,
                rideRecord.getFinalizationAttempts(),
                rideRecord.getFinalizationErrorMessage()
        );
    }
}
