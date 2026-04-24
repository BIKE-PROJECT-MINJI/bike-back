package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(RideRecordFinalizationService.class);

    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final RideRouteCanonicalizer rideRouteCanonicalizer;
    private final BikeMetricsRecorder bikeMetricsRecorder;

    public RideRecordFinalizationService(
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            RideRouteCanonicalizer rideRouteCanonicalizer,
            BikeMetricsRecorder bikeMetricsRecorder
    ) {
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.rideRouteCanonicalizer = rideRouteCanonicalizer;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
    }

    @Async
    public void requestFinalization(Long rideRecordId) {
        finalizeRideRecord(rideRecordId);
    }

    @Transactional
    public void markForRegeneration(RideRecordEntity rideRecord) {
        rideRecord.markFinalizing(OffsetDateTime.now());
        rideRecordRepository.save(rideRecord);
    }

    @Transactional(readOnly = true)
    public RideRecordFinalizationStatusResponse getStatus(RideRecordEntity rideRecord) {
        int rawPointCount = rideRecordPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecord.getId()).size();
        int processedPointCount = rideRecordProcessedPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecord.getId()).size();
        return new RideRecordFinalizationStatusResponse(
                rideRecord.getId(),
                rideRecord.getFinalizationStatus().name(),
                rawPointCount,
                processedPointCount,
                rideRecord.getFinalizationAttempts(),
                rideRecord.getFinalizationErrorMessage()
        );
    }

    @Transactional
    protected void finalizeRideRecord(Long rideRecordId) {
        RideRecordEntity rideRecord = rideRecordRepository.findById(rideRecordId)
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));

        try {
            List<RideRecordPointEntity> rawPoints = rideRecordPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecordId);
            List<RideRecordPointRequest> canonical = rideRouteCanonicalizer.canonicalize(rawPoints.stream()
                    .map(point -> new RideRecordPointRequest(point.getPointOrder(), point.getLatitude(), point.getLongitude()))
                    .toList());
            if (canonical.isEmpty()) {
                throw new IllegalStateException("최종 경로 포인트가 비어 있습니다.");
            }

            rideRecordProcessedPointRepository.deleteByRideRecordId(rideRecordId);
            rideRecordProcessedPointRepository.saveAll(canonical.stream()
                    .map(point -> new RideRecordProcessedPointEntity(rideRecordId, point.pointOrder(), point.latitude(), point.longitude()))
                    .toList());

            rideRecord.markReady(OffsetDateTime.now());
            rideRecordRepository.save(rideRecord);
            log.info("ride record finalization ready rideRecordId={} processedPointCount={}", rideRecordId, canonical.size());
        } catch (Exception exception) {
            rideRecord.markFailed(OffsetDateTime.now(), exception.getMessage());
            rideRecordRepository.save(rideRecord);
            bikeMetricsRecorder.recordRideRecordFinalizationFailure();
            log.error("ride_record_finalization_failed request_id={} ride_record_id={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), rideRecordId, exception);
        }
    }
}
