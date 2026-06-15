package com.bikeprojectminji.bikeback.ride.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RideRecordRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RideRecordRetentionScheduler.class);

    private final RideRecordDeletionService rideRecordDeletionService;

    public RideRecordRetentionScheduler(RideRecordDeletionService rideRecordDeletionService) {
        this.rideRecordDeletionService = rideRecordDeletionService;
    }

    @Scheduled(cron = "${ride-record.retention.cleanup-cron:0 20 3 * * *}")
    public void deleteExpiredRideRecords() {
        int deletedCount = rideRecordDeletionService.deleteExpiredRideRecords();
        log.info("ride_record_retention_cleanup_completed deleted_count={}", deletedCount);
    }
}
