package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordService {

    private static final Logger log = LoggerFactory.getLogger(RideRecordService.class);
    private static final double MIN_POINT_DISTANCE_METERS = 5.0;
    private static final double CANONICAL_EPSILON_METERS = 8.0;

    private final AuthService authService;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RecentLocationCacheService recentLocationCacheService;

    public RideRecordService(
            AuthService authService,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RecentLocationCacheService recentLocationCacheService
    ) {
        this.authService = authService;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.recentLocationCacheService = recentLocationCacheService;
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

        List<RideRecordPointRequest> canonicalRoutePoints = canonicalizeRoutePoints(normalizeRoutePoints(request.routePoints()));
        List<RideRecordPointEntity> routePoints = canonicalRoutePoints.stream()
                .map(point -> new RideRecordPointEntity(rideRecord.getId(), point.pointOrder(), point.latitude(), point.longitude()))
                .toList();
        rideRecordPointRepository.saveAll(routePoints);
        cacheLatestCompletedLocation(subject, rideRecord.getId(), routePoints, request.endedAt());
        log.info("ride record saved subject={} rideRecordId={} routePointCount={} startedAt={} endedAt={}",
                subject, rideRecord.getId(), routePoints.size(), request.startedAt(), request.endedAt());

        return new RideRecordResponse(rideRecord.getId(), user.getId(), routePoints.size());
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
            if (!pointOrders.add(routePoint.pointOrder())) {
                throw new BadRequestException("routePoints의 pointOrder는 중복될 수 없습니다.");
            }
        }
        return routePoints.stream()
                .sorted(Comparator.comparing(RideRecordPointRequest::pointOrder))
                .toList();
    }

    private List<RideRecordPointRequest> canonicalizeRoutePoints(List<RideRecordPointRequest> routePoints) {
        // 현재 저장 경계에서는 raw 포인트를 그대로 영구 저장하지 않고,
        // 시작/끝과 주요 굴곡은 유지하면서 중복·지터와 과도한 직선 구간 포인트를 줄인 canonical path를 만든다.
        List<RideRecordPointRequest> deduplicated = dropNearDuplicatePoints(routePoints);
        List<RideRecordPointRequest> simplified = simplifyWithRamerDouglasPeucker(deduplicated);

        List<RideRecordPointRequest> canonical = new ArrayList<>();
        for (int i = 0; i < simplified.size(); i++) {
            RideRecordPointRequest point = simplified.get(i);
            canonical.add(new RideRecordPointRequest(i + 1, point.latitude(), point.longitude()));
        }
        return canonical;
    }

    private List<RideRecordPointRequest> dropNearDuplicatePoints(List<RideRecordPointRequest> routePoints) {
        if (routePoints.size() <= 2) {
            return routePoints;
        }

        List<RideRecordPointRequest> deduplicated = new ArrayList<>();
        RideRecordPointRequest lastKept = routePoints.get(0);
        deduplicated.add(lastKept);

        for (int i = 1; i < routePoints.size() - 1; i++) {
            RideRecordPointRequest candidate = routePoints.get(i);
            if (distanceMeters(lastKept, candidate) >= MIN_POINT_DISTANCE_METERS) {
                deduplicated.add(candidate);
                lastKept = candidate;
            }
        }

        RideRecordPointRequest lastPoint = routePoints.get(routePoints.size() - 1);
        if (deduplicated.get(deduplicated.size() - 1) != lastPoint) {
            deduplicated.add(lastPoint);
        }
        return deduplicated;
    }

    private List<RideRecordPointRequest> simplifyWithRamerDouglasPeucker(List<RideRecordPointRequest> routePoints) {
        if (routePoints.size() <= 2) {
            return routePoints;
        }

        boolean[] keep = new boolean[routePoints.size()];
        keep[0] = true;
        keep[routePoints.size() - 1] = true;
        markRamerDouglasPeucker(routePoints, 0, routePoints.size() - 1, keep);

        List<RideRecordPointRequest> simplified = new ArrayList<>();
        for (int i = 0; i < routePoints.size(); i++) {
            if (keep[i]) {
                simplified.add(routePoints.get(i));
            }
        }
        return simplified;
    }

    private void markRamerDouglasPeucker(
            List<RideRecordPointRequest> routePoints,
            int startIndex,
            int endIndex,
            boolean[] keep
    ) {
        if (endIndex - startIndex <= 1) {
            return;
        }

        double maxDistance = -1.0;
        int maxDistanceIndex = -1;

        for (int i = startIndex + 1; i < endIndex; i++) {
            double perpendicularDistance = perpendicularDistanceMeters(
                    routePoints.get(startIndex),
                    routePoints.get(endIndex),
                    routePoints.get(i)
            );
            if (perpendicularDistance > maxDistance) {
                maxDistance = perpendicularDistance;
                maxDistanceIndex = i;
            }
        }

        if (maxDistanceIndex != -1 && maxDistance > CANONICAL_EPSILON_METERS) {
            keep[maxDistanceIndex] = true;
            markRamerDouglasPeucker(routePoints, startIndex, maxDistanceIndex, keep);
            markRamerDouglasPeucker(routePoints, maxDistanceIndex, endIndex, keep);
        }
    }

    private double perpendicularDistanceMeters(
            RideRecordPointRequest start,
            RideRecordPointRequest end,
            RideRecordPointRequest candidate
    ) {
        if (start.latitude().compareTo(end.latitude()) == 0 && start.longitude().compareTo(end.longitude()) == 0) {
            return distanceMeters(start, candidate);
        }

        double[] startPoint = toLocalMeters(start, start);
        double[] endPoint = toLocalMeters(start, end);
        double[] candidatePoint = toLocalMeters(start, candidate);

        double lineX = endPoint[0] - startPoint[0];
        double lineY = endPoint[1] - startPoint[1];
        double pointX = candidatePoint[0] - startPoint[0];
        double pointY = candidatePoint[1] - startPoint[1];
        double lineLengthSquared = lineX * lineX + lineY * lineY;

        if (lineLengthSquared == 0.0) {
            return Math.sqrt(pointX * pointX + pointY * pointY);
        }

        double projection = (pointX * lineX + pointY * lineY) / lineLengthSquared;
        if (projection < 0.0) {
            return Math.sqrt(pointX * pointX + pointY * pointY);
        }
        if (projection > 1.0) {
            double dx = candidatePoint[0] - endPoint[0];
            double dy = candidatePoint[1] - endPoint[1];
            return Math.sqrt(dx * dx + dy * dy);
        }

        double projectedX = startPoint[0] + projection * lineX;
        double projectedY = startPoint[1] + projection * lineY;
        double dx = candidatePoint[0] - projectedX;
        double dy = candidatePoint[1] - projectedY;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private double distanceMeters(RideRecordPointRequest start, RideRecordPointRequest end) {
        double lat1 = Math.toRadians(start.latitude().doubleValue());
        double lon1 = Math.toRadians(start.longitude().doubleValue());
        double lat2 = Math.toRadians(end.latitude().doubleValue());
        double lon2 = Math.toRadians(end.longitude().doubleValue());

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000.0 * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private double[] toLocalMeters(RideRecordPointRequest origin, RideRecordPointRequest target) {
        double originLat = Math.toRadians(origin.latitude().doubleValue());
        double originLon = Math.toRadians(origin.longitude().doubleValue());
        double targetLat = Math.toRadians(target.latitude().doubleValue());
        double targetLon = Math.toRadians(target.longitude().doubleValue());
        double meanLat = (originLat + targetLat) / 2.0;
        double x = (targetLon - originLon) * Math.cos(meanLat) * 6371000.0;
        double y = (targetLat - originLat) * 6371000.0;
        return new double[] {x, y};
    }
}
