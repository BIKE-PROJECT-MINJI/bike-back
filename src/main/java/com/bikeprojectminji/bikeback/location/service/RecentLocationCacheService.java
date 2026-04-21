package com.bikeprojectminji.bikeback.location.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RecentLocationCacheService {

    private static final Logger log = LoggerFactory.getLogger(RecentLocationCacheService.class);

    // recent location은 DB의 source of truth를 대체하지 않는 보조 캐시다.
    // 현재 단계에서는 "최근 완료 위치 1건"을 빠르게 조회하는 목적만 가진다.

    private final RecentLocationCacheStore recentLocationCacheStore;

    public RecentLocationCacheService(RecentLocationCacheStore recentLocationCacheStore) {
        this.recentLocationCacheStore = recentLocationCacheStore;
    }

    public Optional<RecentLocationSnapshot> find(String subject) {
        // subject 기준 최근 위치는 현재 단계에서 1건만 유지하므로, 그대로 Optional로 조회한다.
        Optional<RecentLocationSnapshot> snapshot = recentLocationCacheStore.find(subject);
        if (snapshot.isPresent()) {
            RecentLocationSnapshot value = snapshot.get();
            log.info("recent location cache hit subject={} rideRecordId={} pointOrder={} latitude={} longitude={} capturedAt={}",
                    subject, value.rideRecordId(), value.pointOrder(), value.latitude(), value.longitude(), value.capturedAt());
        } else {
            log.info("recent location cache miss subject={}", subject);
        }
        return snapshot;
    }

    public void saveCompleted(
            String subject,
            Long rideRecordId,
            Integer pointOrder,
            BigDecimal latitude,
            BigDecimal longitude,
            OffsetDateTime capturedAt
    ) {
        // 지금은 ACTIVE 세션 전체를 저장하지 않고,
        // 주행 저장이 완료된 시점의 마지막 위치만 COMPLETE 상태로 캐시에 남긴다.
        recentLocationCacheStore.save(subject, new RecentLocationSnapshot(
                rideRecordId,
                latitude,
                longitude,
                pointOrder,
                RecentLocationStatus.COMPLETE,
                capturedAt
        ));
        log.info("recent location cache saved subject={} rideRecordId={} pointOrder={} latitude={} longitude={} capturedAt={}",
                subject, rideRecordId, pointOrder, latitude, longitude, capturedAt);
    }
}
