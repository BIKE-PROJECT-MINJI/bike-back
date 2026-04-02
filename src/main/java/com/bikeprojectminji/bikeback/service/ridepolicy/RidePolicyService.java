package com.bikeprojectminji.bikeback.service.ridepolicy;

import com.bikeprojectminji.bikeback.dto.ridepolicy.RideLocationRequest;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyGateResponse;
import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import com.bikeprojectminji.bikeback.entity.course.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.repository.course.CourseRepository;
import com.bikeprojectminji.bikeback.repository.course.CourseRoutePointRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RidePolicyService {

    private static final Logger log = LoggerFactory.getLogger(RidePolicyService.class);
    private static final BigDecimal START_DISTANCE_THRESHOLD_M = BigDecimal.valueOf(150);
    private static final BigDecimal ACCURACY_THRESHOLD_M = BigDecimal.valueOf(120);
    private static final long STALE_THRESHOLD_SECONDS = 60L;
    private static final BigDecimal OFF_ROUTE_THRESHOLD_M = BigDecimal.valueOf(100);

    private final CourseRepository courseRepository;
    private final CourseRoutePointRepository courseRoutePointRepository;
    private final Clock clock;

    public RidePolicyService(
            CourseRepository courseRepository,
            CourseRoutePointRepository courseRoutePointRepository,
            Clock clock
    ) {
        this.courseRepository = courseRepository;
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.clock = clock;
    }

    public RidePolicyEvaluationResponse evaluate(Long courseId, RidePolicyEvaluationRequest request) {
        validateRequest(request);

        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        List<CourseRoutePointEntity> routePoints = courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId);

        String phase = request.phase();
        RideLocationRequest location = request.location();

        if (isLowAccuracy(location)) {
            log.info("ride policy undetermined: low accuracy courseId={} phase={}", courseId, phase);
            return undeterminedResponse(phase, "LOCATION_LOW_ACCURACY", lowAccuracyMessage(phase));
        }
        if (isStale(location)) {
            log.info("ride policy undetermined: stale location courseId={} phase={}", courseId, phase);
            return undeterminedResponse(phase, "LOCATION_STALE", staleMessage(phase));
        }

        if ("PRE_START".equals(phase)) {
            return evaluatePreStart(course, routePoints, location);
        }
        if ("ACTIVE".equals(phase)) {
            return evaluateActive(courseId, routePoints, location);
        }

        throw new BadRequestException("phase는 PRE_START 또는 ACTIVE여야 합니다.");
    }

    private RidePolicyEvaluationResponse evaluatePreStart(
            CourseEntity course,
            List<CourseRoutePointEntity> routePoints,
            RideLocationRequest location
    ) {
        if (isWithinStartThreshold(course, location) || isWithinRouteThreshold(routePoints, location, START_DISTANCE_THRESHOLD_M)) {
            return new RidePolicyEvaluationResponse(
                    "PRE_START",
                    new RidePolicyGateResponse("ELIGIBLE", "WITHIN_START_OR_ROUTE"),
                    new RidePolicyGateResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                    "PRE_START_ELIGIBLE",
                    "주행을 시작할 수 있습니다."
            );
        }

        return new RidePolicyEvaluationResponse(
                "PRE_START",
                new RidePolicyGateResponse("BLOCKED", "TOO_FAR_FROM_COURSE"),
                new RidePolicyGateResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                "PRE_START_BLOCKED",
                "경로 인근에서 시작해야 합니다. 현재 위치가 선택한 코스와 너무 멉니다."
        );
    }

    private RidePolicyEvaluationResponse evaluateActive(
            Long courseId,
            List<CourseRoutePointEntity> routePoints,
            RideLocationRequest location
    ) {
        if (routePoints.isEmpty()) {
            log.warn("ride policy undetermined: course path unavailable courseId={}", courseId);
            return new RidePolicyEvaluationResponse(
                    "ACTIVE",
                    new RidePolicyGateResponse("ELIGIBLE", "ALREADY_ACTIVE"),
                    new RidePolicyGateResponse("UNDETERMINED", "COURSE_PATH_INVALID"),
                    "ACTIVE_UNDETERMINED",
                    "코스 경로 정보를 확인할 수 없어 이탈 여부를 판단하기 어렵습니다."
            );
        }

        if (isWithinRouteThreshold(routePoints, location, OFF_ROUTE_THRESHOLD_M)) {
            return new RidePolicyEvaluationResponse(
                    "ACTIVE",
                    new RidePolicyGateResponse("ELIGIBLE", "ALREADY_ACTIVE"),
                    new RidePolicyGateResponse("ON_ROUTE", "WITHIN_ROUTE_THRESHOLD"),
                    "ACTIVE_ON_ROUTE",
                    "현재 코스를 따라 주행 중입니다."
            );
        }

        return new RidePolicyEvaluationResponse(
                "ACTIVE",
                new RidePolicyGateResponse("ELIGIBLE", "ALREADY_ACTIVE"),
                new RidePolicyGateResponse("WARNING", "OFF_ROUTE_THRESHOLD_EXCEEDED"),
                "ACTIVE_WARNING",
                "경로를 벗어났습니다. 코스 라인으로 복귀하세요."
        );
    }

    private RidePolicyEvaluationResponse undeterminedResponse(String phase, String reasonCode, String message) {
        return new RidePolicyEvaluationResponse(
                phase,
                new RidePolicyGateResponse("UNDETERMINED", reasonCode),
                new RidePolicyGateResponse("UNDETERMINED", "PRE_START".equals(phase) ? "NOT_ACTIVE_YET" : reasonCode),
                phase + "_UNDETERMINED",
                message
        );
    }

    private void validateRequest(RidePolicyEvaluationRequest request) {
        if (request == null || request.phase() == null || request.location() == null) {
            throw new BadRequestException("phase와 location은 필수입니다.");
        }
        RideLocationRequest location = request.location();
        if (location.lat() == null || location.lon() == null || location.accuracyM() == null || location.capturedAt() == null) {
            throw new BadRequestException("location.lat, location.lon, location.accuracyM, location.capturedAt는 필수입니다.");
        }
    }

    private boolean isLowAccuracy(RideLocationRequest location) {
        return location.accuracyM().compareTo(ACCURACY_THRESHOLD_M) > 0;
    }

    private boolean isStale(RideLocationRequest location) {
        long age = Duration.between(location.capturedAt().toInstant(), clock.instant()).getSeconds();
        return age > STALE_THRESHOLD_SECONDS;
    }

    private boolean isWithinStartThreshold(CourseEntity course, RideLocationRequest location) {
        if (course.getStartLatitude() == null || course.getStartLongitude() == null) {
            return false;
        }
        return distanceMeters(
                location.lat().doubleValue(),
                location.lon().doubleValue(),
                course.getStartLatitude().doubleValue(),
                course.getStartLongitude().doubleValue()
        ).compareTo(START_DISTANCE_THRESHOLD_M) <= 0;
    }

    private boolean isWithinRouteThreshold(List<CourseRoutePointEntity> routePoints, RideLocationRequest location, BigDecimal thresholdMeters) {
        if (routePoints.isEmpty()) {
            return false;
        }
        return minimumRouteDistanceMeters(routePoints, location).compareTo(thresholdMeters) <= 0;
    }

    private BigDecimal minimumRouteDistanceMeters(List<CourseRoutePointEntity> routePoints, RideLocationRequest location) {
        if (routePoints.size() == 1) {
            CourseRoutePointEntity point = routePoints.get(0);
            return distanceMeters(
                    location.lat().doubleValue(),
                    location.lon().doubleValue(),
                    point.getLatitude().doubleValue(),
                    point.getLongitude().doubleValue()
            );
        }

        double minDistance = Double.MAX_VALUE;
        for (int i = 0; i < routePoints.size() - 1; i++) {
            CourseRoutePointEntity start = routePoints.get(i);
            CourseRoutePointEntity end = routePoints.get(i + 1);
            minDistance = Math.min(
                    minDistance,
                    segmentDistanceMeters(
                            location.lat().doubleValue(),
                            location.lon().doubleValue(),
                            start.getLatitude().doubleValue(),
                            start.getLongitude().doubleValue(),
                            end.getLatitude().doubleValue(),
                            end.getLongitude().doubleValue()
                    )
            );
        }
        return BigDecimal.valueOf(minDistance).setScale(0, RoundingMode.HALF_UP);
    }

    private double segmentDistanceMeters(
            double pointLat,
            double pointLon,
            double startLat,
            double startLon,
            double endLat,
            double endLon
    ) {
        double referenceLat = Math.toRadians((startLat + endLat + pointLat) / 3.0);
        double meterPerDegLat = 111_320d;
        double meterPerDegLon = Math.cos(referenceLat) * 111_320d;

        double px = pointLon * meterPerDegLon;
        double py = pointLat * meterPerDegLat;
        double x1 = startLon * meterPerDegLon;
        double y1 = startLat * meterPerDegLat;
        double x2 = endLon * meterPerDegLon;
        double y2 = endLat * meterPerDegLat;

        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0d && dy == 0d) {
            return Math.hypot(px - x1, py - y1);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0d, Math.min(1d, t));

        double closestX = x1 + t * dx;
        double closestY = y1 + t * dy;
        return Math.hypot(px - closestX, py - closestY);
    }

    private BigDecimal distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return BigDecimal.valueOf(earthRadiusMeters * c).setScale(0, RoundingMode.HALF_UP);
    }

    private String lowAccuracyMessage(String phase) {
        return "PRE_START".equals(phase)
                ? "위치 정확도가 낮아 시작 가능 여부를 판단하기 어렵습니다."
                : "현재 위치 정확도가 낮아 경로 이탈 여부를 판단하기 어렵습니다.";
    }

    private String staleMessage(String phase) {
        return "PRE_START".equals(phase)
                ? "위치 정보가 오래되어 시작 가능 여부를 판단하기 어렵습니다."
                : "위치 정보가 오래되어 경로 이탈 여부를 판단하기 어렵습니다.";
    }
}
