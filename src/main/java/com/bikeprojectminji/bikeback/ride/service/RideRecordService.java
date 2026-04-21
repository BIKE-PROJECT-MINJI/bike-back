package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordService {

    private static final Logger log = LoggerFactory.getLogger(RideRecordService.class);

    private final AuthService authService;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RecentLocationCacheService recentLocationCacheService;
    private final RideRecordFinalizationService rideRecordFinalizationService;

    public RideRecordService(
            AuthService authService,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RecentLocationCacheService recentLocationCacheService,
            RideRecordFinalizationService rideRecordFinalizationService
    ) {
        this.authService = authService;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.recentLocationCacheService = recentLocationCacheService;
        this.rideRecordFinalizationService = rideRecordFinalizationService;
    }

    @Transactional
    public RideRecordResponse saveRideRecord(String subject, CreateRideRecordRequest request) {
        // 자유 주행 저장은 입력 검증 -> 현재 사용자 식별 -> ride record 저장 -> route point 저장 순서로 진행한다.
        // point와 summary는 항상 DB가 원본이고, 캐시는 후속 조회 최적화 용도로만 갱신한다.
        validateCreateRequest(request);
        UserEntity user = authService.findUserBySubject(subject);

        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                user.getId(),
                request.startedAt(),
                request.endedAt(),
                request.summary().distanceM(),
                request.summary().durationSec()
        ));

        rideRecord.markFinalizing(OffsetDateTime.now());
        rideRecord = rideRecordRepository.save(rideRecord);
        Long rideRecordId = rideRecord.getId();

        List<RideRecordPointRequest> normalizedRoutePoints = normalizeRoutePoints(request.routePoints());
        List<RideRecordPointEntity> routePoints = normalizedRoutePoints.stream()
                .map(point -> new RideRecordPointEntity(
                        rideRecordId,
                        point.pointOrder(),
                        point.latitude(),
                        point.longitude(),
                        point.capturedAt(),
                        point.accuracyM(),
                        point.speedMps(),
                        point.bearingDeg(),
                        point.altitudeM(),
                        point.distanceToRouteM(),
                        point.routeProgressPct()
                ))
                .toList();
        rideRecordPointRepository.saveAll(routePoints);
        cacheLatestCompletedLocation(subject, rideRecordId, routePoints, request.endedAt());
        registerFinalizationAfterCommit(rideRecordId);
        log.info("ride record saved subject={} rideRecordId={} routePointCount={} startedAt={} endedAt={}",
                subject, rideRecordId, routePoints.size(), request.startedAt(), request.endedAt());

        return new RideRecordResponse(rideRecordId, user.getId(), routePoints.size(), rideRecord.getFinalizationStatus().name());
    }

    @Transactional(readOnly = true)
    public RideRecordFinalizationStatusResponse getRideRecordStatus(String subject, Long rideRecordId) {
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(rideRecordId, user.getId())
                .orElseThrow(() -> new BadRequestException("자유 주행 기록을 찾을 수 없습니다."));
        return rideRecordFinalizationService.getStatus(rideRecord);
    }

    @Transactional
    public RideRecordFinalizationStatusResponse regenerateRideRecord(String subject, Long rideRecordId) {
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(rideRecordId, user.getId())
                .orElseThrow(() -> new BadRequestException("자유 주행 기록을 찾을 수 없습니다."));
        rideRecordFinalizationService.markForRegeneration(rideRecord);
        rideRecordFinalizationService.requestFinalization(rideRecordId);
        return rideRecordFinalizationService.getStatus(rideRecord);
    }

    private void cacheLatestCompletedLocation(
            String subject,
            Long rideRecordId,
            List<RideRecordPointEntity> routePoints,
            OffsetDateTime endedAt
    ) {
        // 최종 기록은 DB가 원본이고, 최근 위치 조회만 빠르게 하기 위해 마지막 포인트를 보조 캐시에 남긴다.
        if (routePoints.isEmpty()) {
            return;
        }
        RideRecordPointEntity latestPoint = routePoints.get(routePoints.size() - 1);
        recentLocationCacheService.saveCompleted(
                subject,
                rideRecordId,
                latestPoint.getPointOrder(),
                latestPoint.getLatitude(),
                latestPoint.getLongitude(),
                endedAt
        );
    }

    private void validateCreateRequest(CreateRideRecordRequest request) {
        // 저장 요청은 startedAt/endedAt/summary/routePoints가 모두 있어야 하고,
        // 음수 거리/시간이나 비정상 pointOrder는 여기서 조기에 차단한다.
        if (request == null) {
            throw new BadRequestException("자유 주행 기록 요청 본문이 필요합니다.");
        }
        if (request.startedAt() == null || request.endedAt() == null) {
            throw new BadRequestException("startedAt과 endedAt은 비어 있을 수 없습니다.");
        }
        if (request.summary() == null) {
            throw new BadRequestException("summary는 비어 있을 수 없습니다.");
        }
        if (request.summary().distanceM() == null || request.summary().distanceM() < 0) {
            throw new BadRequestException("distanceM은 0 이상이어야 합니다.");
        }
        if (request.summary().durationSec() == null || request.summary().durationSec() < 0) {
            throw new BadRequestException("durationSec은 0 이상이어야 합니다.");
        }
        normalizeRoutePoints(request.routePoints());
    }

    private List<RideRecordPointRequest> normalizeRoutePoints(List<RideRecordPointRequest> routePoints) {
        // route point는 입력 순서가 어떻든 pointOrder 기준으로 다시 정렬하고,
        // 중복 pointOrder와 누락 좌표를 여기서 한 번에 검증한다.
        if (routePoints == null || routePoints.isEmpty()) {
            throw new BadRequestException("routePoints는 비어 있을 수 없습니다.");
        }
        Set<Integer> pointOrders = new HashSet<>();
        for (RideRecordPointRequest routePoint : routePoints) {
            if (routePoint.pointOrder() == null || routePoint.pointOrder() < 1) {
                throw new BadRequestException("pointOrder는 1 이상이어야 합니다.");
            }
            if (routePoint.latitude() == null || routePoint.longitude() == null) {
                throw new BadRequestException("routePoints의 latitude와 longitude는 비어 있을 수 없습니다.");
            }
            if (routePoint.accuracyM() != null && routePoint.accuracyM().signum() < 0) {
                throw new BadRequestException("accuracyM은 0 이상이어야 합니다.");
            }
            if (routePoint.speedMps() != null && routePoint.speedMps().signum() < 0) {
                throw new BadRequestException("speedMps는 0 이상이어야 합니다.");
            }
            if (routePoint.distanceToRouteM() != null && routePoint.distanceToRouteM().signum() < 0) {
                throw new BadRequestException("distanceToRouteM은 0 이상이어야 합니다.");
            }
            if (routePoint.bearingDeg() != null && (routePoint.bearingDeg().signum() < 0 || routePoint.bearingDeg().compareTo(java.math.BigDecimal.valueOf(360)) >= 0)) {
                throw new BadRequestException("bearingDeg는 0 이상 360 미만이어야 합니다.");
            }
            if (routePoint.routeProgressPct() != null && (routePoint.routeProgressPct().signum() < 0 || routePoint.routeProgressPct().compareTo(java.math.BigDecimal.valueOf(100)) > 0)) {
                throw new BadRequestException("routeProgressPct는 0 이상 100 이하여야 합니다.");
            }
            if (!pointOrders.add(routePoint.pointOrder())) {
                throw new BadRequestException("routePoints의 pointOrder는 중복될 수 없습니다.");
            }
        }
        return routePoints.stream()
                .sorted(Comparator.comparing(RideRecordPointRequest::pointOrder))
                .toList();
    }

    private void registerFinalizationAfterCommit(Long rideRecordId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rideRecordFinalizationService.requestFinalization(rideRecordId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rideRecordFinalizationService.requestFinalization(rideRecordId);
            }
        });
    }

}
