package com.bikeprojectminji.bikeback.location.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RecentLocationCacheService {

    private final RecentLocationCacheStore recentLocationCacheStore;

    public RecentLocationCacheService(RecentLocationCacheStore recentLocationCacheStore) {
        this.recentLocationCacheStore = recentLocationCacheStore;
    }

    public Optional<RecentLocationSnapshot> find(String subject) {
        return recentLocationCacheStore.find(subject);
    }

    public void saveCompleted(
            String subject,
            Long rideRecordId,
            Integer pointOrder,
            BigDecimal latitude,
            BigDecimal longitude,
            OffsetDateTime capturedAt
    ) {
        recentLocationCacheStore.save(subject, new RecentLocationSnapshot(
                rideRecordId,
                latitude,
                longitude,
                pointOrder,
                RecentLocationStatus.COMPLETE,
                capturedAt
        ));
    }
}
