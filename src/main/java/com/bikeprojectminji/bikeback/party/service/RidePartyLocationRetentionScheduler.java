package com.bikeprojectminji.bikeback.party.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RidePartyLocationRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RidePartyLocationRetentionScheduler.class);

    private final RidePartyLocationService locationService;

    public RidePartyLocationRetentionScheduler(RidePartyLocationService locationService) {
        this.locationService = locationService;
    }

    @Scheduled(cron = "${party.location.retention.cleanup-cron:0 10 * * * *}")
    public void deleteExpiredLocationPoints() {
        int deletedCount = locationService.deleteExpiredLocationPoints();
        log.info("ride_party_location_retention_cleanup_completed deleted_count={}", deletedCount);
    }
}
