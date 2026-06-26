package com.bikeprojectminji.bikeback.location.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final AuthService authService;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;

    public RecentLocationCacheService(
            RecentLocationCacheStore recentLocationCacheStore,
            AuthService authService,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository
    ) {
        this.recentLocationCacheStore = recentLocationCacheStore;
        this.authService = authService;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
    }

    @MeasuredOperation("location.recent.find")
    public Optional<RecentLocationSnapshot> find(String subject) {
        // subject 기준 최근 위치는 현재 단계에서 1건만 유지하므로, 그대로 Optional로 조회한다.
        Optional<RecentLocationSnapshot> snapshot = recentLocationCacheStore.find(subject);
        if (snapshot.isPresent()) {
            RecentLocationSnapshot value = snapshot.get();
            log.info("recent location cache hit subjectHash={} rideRecordId={} pointOrder={} capturedAt={}",
                    hashSubject(subject), value.rideRecordId(), value.pointOrder(), value.capturedAt());
            return snapshot;
        }
        log.info("recent location cache miss subjectHash={}", hashSubject(subject));
        return findLatestReadyRideLocation(subject);
    }

    private Optional<RecentLocationSnapshot> findLatestReadyRideLocation(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        Optional<RideRecordEntity> latestRideRecord = rideRecordRepository.findFirstByOwnerUserIdAndFinalizationStatusOrderByEndedAtDescIdDesc(
                user.getId(),
                RideRecordFinalizationStatus.READY.name()
        );
        if (latestRideRecord.isEmpty()) {
            log.info("recent location db fallback miss subjectHash={} reason=no_ready_ride_record", hashSubject(subject));
            return Optional.empty();
        }

        RideRecordEntity rideRecord = latestRideRecord.get();
        Optional<RideRecordPointEntity> lastPoint = rideRecordPointRepository.findTopByRideRecordIdOrderByPointOrderDesc(rideRecord.getId());
        if (lastPoint.isEmpty()) {
            log.info("recent location db fallback miss subjectHash={} rideRecordId={} reason=no_route_point", hashSubject(subject), rideRecord.getId());
            return Optional.empty();
        }

        RideRecordPointEntity point = lastPoint.get();
        RecentLocationSnapshot fallback = new RecentLocationSnapshot(
                rideRecord.getId(),
                point.getLatitude(),
                point.getLongitude(),
                point.getPointOrder(),
                RecentLocationStatus.COMPLETE,
                point.getCapturedAt() != null ? point.getCapturedAt() : rideRecord.getEndedAt()
        );
        recentLocationCacheStore.save(subject, fallback);
        log.info("recent location db fallback hit subjectHash={} rideRecordId={} pointOrder={} capturedAt={}",
                hashSubject(subject), fallback.rideRecordId(), fallback.pointOrder(), fallback.capturedAt());
        return Optional.of(fallback);
    }

    @MeasuredOperation("location.recent.save_completed")
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
        log.info("recent location cache saved subjectHash={} rideRecordId={} pointOrder={} capturedAt={}",
                hashSubject(subject), rideRecordId, pointOrder, capturedAt);
    }

    private String hashSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(subject.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 6 && index < bytes.length; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
    }
}
