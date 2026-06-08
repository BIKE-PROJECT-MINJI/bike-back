package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordFinalizationWriter {

    private static final Logger log = LoggerFactory.getLogger(RideRecordFinalizationWriter.class);

    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final RideRouteCanonicalizer rideRouteCanonicalizer;

    public RideRecordFinalizationWriter(
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            RideRouteCanonicalizer rideRouteCanonicalizer
    ) {
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.rideRouteCanonicalizer = rideRouteCanonicalizer;
    }

    @Transactional
    public void replaceProcessedPoints(Long rideRecordId) {
        RideRecordEntity rideRecord = rideRecordRepository.findByIdForUpdate(rideRecordId)
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        if (rideRecord.getFinalizationStatus() != RideRecordFinalizationStatus.FINALIZING) {
            log.info("ride record finalization skipped rideRecordId={} status={}", rideRecordId, rideRecord.getFinalizationStatus());
            return;
        }

        List<RideRecordPointEntity> rawPoints = rideRecordPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecordId);
        List<RideRecordPointRequest> canonical = rideRouteCanonicalizer.canonicalize(rawPoints.stream()
                .map(point -> new RideRecordPointRequest(point.getPointOrder(), point.getLatitude(), point.getLongitude()))
                .toList());
        if (canonical.isEmpty()) {
            throw new IllegalStateException("최종 경로 포인트가 비어 있습니다.");
        }

        rideRecordProcessedPointRepository.deleteByRideRecordId(rideRecordId);
        rideRecordProcessedPointRepository.flush();
        rideRecordProcessedPointRepository.saveAll(canonical.stream()
                .map(point -> new RideRecordProcessedPointEntity(rideRecordId, point.pointOrder(), point.latitude(), point.longitude()))
                .toList());

        rideRecord.markReady(OffsetDateTime.now());
        rideRecordRepository.save(rideRecord);
        log.info("ride record finalization ready rideRecordId={} processedPointCount={}", rideRecordId, canonical.size());
    }
}
