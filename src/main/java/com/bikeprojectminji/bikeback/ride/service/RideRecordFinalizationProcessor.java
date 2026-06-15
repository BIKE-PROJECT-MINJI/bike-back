package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RideRecordFinalizationProcessor {

    private static final Logger log = LoggerFactory.getLogger(RideRecordFinalizationProcessor.class);

    private final RideRecordFinalizationWriter rideRecordFinalizationWriter;
    private final RideRecordFinalizationFailureService rideRecordFinalizationFailureService;
    private final BikeMetricsRecorder bikeMetricsRecorder;

    public RideRecordFinalizationProcessor(
            RideRecordFinalizationWriter rideRecordFinalizationWriter,
            RideRecordFinalizationFailureService rideRecordFinalizationFailureService,
            BikeMetricsRecorder bikeMetricsRecorder
    ) {
        this.rideRecordFinalizationWriter = rideRecordFinalizationWriter;
        this.rideRecordFinalizationFailureService = rideRecordFinalizationFailureService;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
    }

    public void finalizeRideRecord(Long rideRecordId) {
        try {
            rideRecordFinalizationWriter.replaceProcessedPoints(rideRecordId);
        } catch (Exception exception) {
            rideRecordFinalizationFailureService.markFailed(rideRecordId, exception.getMessage());
            bikeMetricsRecorder.recordRideRecordFinalizationFailure();
            log.error("ride_record_finalization_failed request_id={} ride_record_id={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), rideRecordId, exception);
        }
    }
}
