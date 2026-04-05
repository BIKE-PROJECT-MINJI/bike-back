package com.bikeprojectminji.bikeback.location.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class RecentLocationCacheService {

    // recent location은 DB의 source of truth를 대체하지 않는 보조 캐시다.
    // 현재 단계에서는 "최근 완료 위치 1건"을 빠르게 조회하는 목적만 가진다.

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
