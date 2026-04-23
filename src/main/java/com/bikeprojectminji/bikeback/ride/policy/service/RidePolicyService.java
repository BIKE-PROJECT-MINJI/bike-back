package com.bikeprojectminji.bikeback.ride.policy.service;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.ride.policy.dto.RideLocationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyCompletionResponse;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyGateResponse;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyOffRouteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RidePolicyService {

    private static final Logger log = LoggerFactory.getLogger(RidePolicyService.class);

    private static final BigDecimal START_DISTANCE_THRESHOLD_M = BigDecimal.valueOf(50);
    private static final BigDecimal PRE_START_ACCURACY_THRESHOLD_M = BigDecimal.valueOf(120);
    private static final long PRE_START_STALE_THRESHOLD_SECONDS = 60L;
    private static final BigDecimal ACTIVE_ACCURACY_THRESHOLD_M = BigDecimal.valueOf(50);
    private static final long ACTIVE_STALE_THRESHOLD_SECONDS = 15L;
    private static final int OFF_ROUTE_CANDIDATE_THRESHOLD_M = 50;
    private static final int OFF_ROUTE_WARNING_THRESHOLD_SECONDS = 15;
    private static final int OFF_ROUTE_RECOVERY_THRESHOLD_M = 30;
    private static final int COMPLETION_COVERAGE_THRESHOLD_PERCENT = 80;
    private static final int COMPLETION_DISTANCE_THRESHOLD_M = 150;
    private static final int LOOP_START_EXIT_THRESHOLD_M = 250;
    private static final int LOOP_DETECTION_THRESHOLD_M = 150;

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

        if (isLowAccuracy(phase, location)) {
            log.info("ride_policy_undetermined request_id={} reason=low_accuracy course_id={} phase={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), courseId, phase);
            return undeterminedResponse(phase, "LOCATION_LOW_ACCURACY", lowAccuracyMessage(phase));
        }
        if (isStale(phase, location)) {
            log.info("ride_policy_undetermined request_id={} reason=stale_location course_id={} phase={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), courseId, phase);
            return undeterminedResponse(phase, "LOCATION_STALE", staleMessage(phase));
        }

        if ("PRE_START".equals(phase)) {
            return evaluatePreStart(course, location);
        }
        if ("ACTIVE".equals(phase)) {
            return evaluateActive(courseId, course, routePoints, request);
        }

        throw new BadRequestException("phase는 PRE_START 또는 ACTIVE여야 합니다.");
    }

    private RidePolicyEvaluationResponse evaluatePreStart(CourseEntity course, RideLocationRequest location) {
        BigDecimal startPointDistance = startPointDistanceMeters(course, location);
        if (startPointDistance != null && startPointDistance.compareTo(START_DISTANCE_THRESHOLD_M) <= 0) {
            return new RidePolicyEvaluationResponse(
                    "PRE_START",
                    new RidePolicyGateResponse(
                            "ELIGIBLE",
                            "WITHIN_START_POINT_THRESHOLD",
                            startPointDistance.intValue(),
                            START_DISTANCE_THRESHOLD_M.intValue()
                    ),
                    new RidePolicyOffRouteResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                    new RidePolicyCompletionResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                    "PRE_START_ELIGIBLE",
                    "코스 시작점 근처이므로 주행을 시작할 수 있습니다."
            );
        }

        return new RidePolicyEvaluationResponse(
                "PRE_START",
                new RidePolicyGateResponse(
                        "BLOCKED",
                        "START_POINT_THRESHOLD_EXCEEDED",
                        startPointDistance != null ? startPointDistance.intValue() : null,
                        START_DISTANCE_THRESHOLD_M.intValue()
                ),
                new RidePolicyOffRouteResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                new RidePolicyCompletionResponse("UNDETERMINED", "NOT_ACTIVE_YET"),
                "PRE_START_BLOCKED",
                "코스 시작점 50m 이내에서만 주행을 시작할 수 있습니다. 시작점 근처로 이동해 주세요."
        );
    }

    private RidePolicyEvaluationResponse evaluateActive(
            Long courseId,
            CourseEntity course,
            List<CourseRoutePointEntity> routePoints,
            RidePolicyEvaluationRequest request
    ) {
        if (routePoints.isEmpty()) {
            log.warn("ride_policy_undetermined request_id={} reason=course_path_unavailable course_id={}", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId(), courseId);
            return new RidePolicyEvaluationResponse(
                    "ACTIVE",
                    new RidePolicyGateResponse("ELIGIBLE", "ALREADY_ACTIVE"),
                    new RidePolicyOffRouteResponse("UNDETERMINED", "COURSE_PATH_INVALID"),
                    new RidePolicyCompletionResponse("UNDETERMINED", "COURSE_PATH_INVALID"),
                    "ACTIVE_UNDETERMINED",
                    "코스 경로 정보를 확인할 수 없어 주행 정책을 판단하기 어렵습니다."
            );
        }

        List<RideLocationRequest> trace = normalizedTrace(request);
        OffRouteDecision offRouteDecision = evaluateOffRoute(routePoints, trace);
        CompletionDecision completionDecision = evaluateCompletion(course, routePoints, trace, request.location());

        return new RidePolicyEvaluationResponse(
                "ACTIVE",
                new RidePolicyGateResponse("ELIGIBLE", "ALREADY_ACTIVE"),
                offRouteDecision.response(),
                completionDecision.response(),
                overallState(offRouteDecision.response(), completionDecision.response()),
                defaultMessage(offRouteDecision.response(), completionDecision.response())
        );
    }

    private RidePolicyEvaluationResponse undeterminedResponse(String phase, String reasonCode, String message) {
        return new RidePolicyEvaluationResponse(
                phase,
                new RidePolicyGateResponse("UNDETERMINED", reasonCode),
                new RidePolicyOffRouteResponse("UNDETERMINED", "PRE_START".equals(phase) ? "NOT_ACTIVE_YET" : reasonCode),
                new RidePolicyCompletionResponse("UNDETERMINED", "PRE_START".equals(phase) ? "NOT_ACTIVE_YET" : reasonCode),
                phase + "_UNDETERMINED",
                message
        );
    }

    private void validateRequest(RidePolicyEvaluationRequest request) {
        if (request == null || request.phase() == null || request.location() == null) {
            throw new BadRequestException("phase와 location은 필수입니다.");
        }
        validateLocation(request.location());
        if (request.trace() != null) {
            for (RideLocationRequest tracePoint : request.trace()) {
                validateLocation(tracePoint);
            }
        }
    }

    private void validateLocation(RideLocationRequest location) {
        if (location.lat() == null || location.lon() == null || location.accuracyM() == null || location.capturedAt() == null) {
            throw new BadRequestException("location.lat, location.lon, location.accuracyM, location.capturedAt는 필수입니다.");
        }
    }

    private List<RideLocationRequest> normalizedTrace(RidePolicyEvaluationRequest request) {
        List<RideLocationRequest> trace = new ArrayList<>();
        if (request.trace() != null) {
            trace.addAll(request.trace());
        }
        trace.add(request.location());
        trace.sort(Comparator.comparing(RideLocationRequest::capturedAt));
        return trace;
    }

    private OffRouteDecision evaluateOffRoute(List<CourseRoutePointEntity> routePoints, List<RideLocationRequest> trace) {
        OffRouteState state = OffRouteState.ON_ROUTE;
        OffsetDateTime candidateStartedAt = null;
        boolean recovered = false;

        int currentDistance = 0;
        for (RideLocationRequest tracePoint : trace) {
            int distance = minimumRouteDistanceMeters(routePoints, tracePoint).intValue();
            currentDistance = distance;

            if (state == OffRouteState.ON_ROUTE) {
                if (distance > OFF_ROUTE_CANDIDATE_THRESHOLD_M) {
                    state = OffRouteState.CANDIDATE;
                    candidateStartedAt = tracePoint.capturedAt();
                }
                continue;
            }

            if (distance <= OFF_ROUTE_RECOVERY_THRESHOLD_M) {
                state = OffRouteState.ON_ROUTE;
                candidateStartedAt = null;
                recovered = true;
                continue;
            }

            if (state == OffRouteState.CANDIDATE
                    && candidateStartedAt != null
                    && Duration.between(candidateStartedAt.toInstant(), tracePoint.capturedAt().toInstant()).getSeconds() >= OFF_ROUTE_WARNING_THRESHOLD_SECONDS) {
                state = OffRouteState.WARNING;
            }
        }

        int durationSec = 0;
        if (candidateStartedAt != null && state != OffRouteState.ON_ROUTE) {
            durationSec = (int) Duration.between(candidateStartedAt.toInstant(), trace.get(trace.size() - 1).capturedAt().toInstant()).getSeconds();
        }

        if (state == OffRouteState.WARNING) {
            String reasonCode = currentDistance > OFF_ROUTE_CANDIDATE_THRESHOLD_M
                    ? "OFF_ROUTE_WARNING_ACTIVE"
                    : "OFF_ROUTE_RECOVERY_PENDING";
            return new OffRouteDecision(new RidePolicyOffRouteResponse(
                    "WARNING",
                    reasonCode,
                    currentDistance,
                    OFF_ROUTE_CANDIDATE_THRESHOLD_M,
                    OFF_ROUTE_WARNING_THRESHOLD_SECONDS,
                    OFF_ROUTE_RECOVERY_THRESHOLD_M,
                    durationSec
            ));
        }

        if (state == OffRouteState.CANDIDATE) {
            return new OffRouteDecision(new RidePolicyOffRouteResponse(
                    "CANDIDATE",
                    "OFF_ROUTE_CANDIDATE_ACTIVE",
                    currentDistance,
                    OFF_ROUTE_CANDIDATE_THRESHOLD_M,
                    OFF_ROUTE_WARNING_THRESHOLD_SECONDS,
                    OFF_ROUTE_RECOVERY_THRESHOLD_M,
                    durationSec
            ));
        }

        return new OffRouteDecision(new RidePolicyOffRouteResponse(
                "ON_ROUTE",
                recovered ? "RECOVERED_WITHIN_THRESHOLD" : "WITHIN_ROUTE_THRESHOLD",
                currentDistance,
                OFF_ROUTE_CANDIDATE_THRESHOLD_M,
                OFF_ROUTE_WARNING_THRESHOLD_SECONDS,
                OFF_ROUTE_RECOVERY_THRESHOLD_M,
                0
        ));
    }

    private CompletionDecision evaluateCompletion(
            CourseEntity course,
            List<CourseRoutePointEntity> routePoints,
            List<RideLocationRequest> trace,
            RideLocationRequest currentLocation
    ) {
        if (routePoints.size() < 2) {
            return new CompletionDecision(new RidePolicyCompletionResponse("UNDETERMINED", "COURSE_PATH_INVALID", 0, COMPLETION_COVERAGE_THRESHOLD_PERCENT, false, false, null, COMPLETION_DISTANCE_THRESHOLD_M));
        }

        List<RouteSegment> segments = buildSegments(routePoints);
        double totalLength = totalLength(segments);
        int coveragePercent = coveragePercent(segments, trace, totalLength);
        boolean loopCourse = isLoopCourse(routePoints);
        int startDistance = distanceFromStart(course, routePoints, currentLocation);
        int endDistance = distanceFromEnd(routePoints, currentLocation);
        boolean leftStartZone = hasLeftStartZone(course, routePoints, trace);

        if (coveragePercent < COMPLETION_COVERAGE_THRESHOLD_PERCENT) {
            return new CompletionDecision(new RidePolicyCompletionResponse(
                    "IN_PROGRESS",
                    "COVERAGE_BELOW_THRESHOLD",
                    coveragePercent,
                    COMPLETION_COVERAGE_THRESHOLD_PERCENT,
                    loopCourse,
                    leftStartZone,
                    loopCourse ? startDistance : endDistance,
                    COMPLETION_DISTANCE_THRESHOLD_M
            ));
        }

        if (loopCourse) {
            if (!leftStartZone) {
                return new CompletionDecision(new RidePolicyCompletionResponse(
                        "IN_PROGRESS",
                        "START_ZONE_NOT_EXITED",
                        coveragePercent,
                        COMPLETION_COVERAGE_THRESHOLD_PERCENT,
                        true,
                        false,
                        startDistance,
                        LOOP_START_EXIT_THRESHOLD_M
                ));
            }

            if (startDistance > COMPLETION_DISTANCE_THRESHOLD_M) {
                return new CompletionDecision(new RidePolicyCompletionResponse(
                        "IN_PROGRESS",
                        "AWAITING_RETURN_TO_START",
                        coveragePercent,
                        COMPLETION_COVERAGE_THRESHOLD_PERCENT,
                        true,
                        true,
                        startDistance,
                        COMPLETION_DISTANCE_THRESHOLD_M
                ));
            }

            return new CompletionDecision(new RidePolicyCompletionResponse(
                    "ELIGIBLE",
                    "LOOP_COMPLETION_READY",
                    coveragePercent,
                    COMPLETION_COVERAGE_THRESHOLD_PERCENT,
                    true,
                    true,
                    startDistance,
                    COMPLETION_DISTANCE_THRESHOLD_M
            ));
        }

        if (endDistance > COMPLETION_DISTANCE_THRESHOLD_M) {
            return new CompletionDecision(new RidePolicyCompletionResponse(
                    "IN_PROGRESS",
                    "AWAITING_DESTINATION",
                    coveragePercent,
                    COMPLETION_COVERAGE_THRESHOLD_PERCENT,
                    false,
                    leftStartZone,
                    endDistance,
                    COMPLETION_DISTANCE_THRESHOLD_M
            ));
        }

        return new CompletionDecision(new RidePolicyCompletionResponse(
                "ELIGIBLE",
                "NON_LOOP_COMPLETION_READY",
                coveragePercent,
                COMPLETION_COVERAGE_THRESHOLD_PERCENT,
                false,
                leftStartZone,
                endDistance,
                COMPLETION_DISTANCE_THRESHOLD_M
        ));
    }

    private int coveragePercent(List<RouteSegment> segments, List<RideLocationRequest> trace, double totalLength) {
        if (totalLength <= 0d) {
            return 0;
        }

        List<Range> ranges = new ArrayList<>();
        Projection previous = null;
        for (RideLocationRequest tracePoint : trace) {
            Projection current = projectOntoRoute(segments, tracePoint);
            if (current == null) {
                previous = null;
                continue;
            }
            if (previous != null) {
                ranges.add(new Range(Math.min(previous.distanceAlongRouteM(), current.distanceAlongRouteM()), Math.max(previous.distanceAlongRouteM(), current.distanceAlongRouteM())));
            }
            previous = current;
        }

        if (ranges.isEmpty()) {
            return 0;
        }

        ranges.sort(Comparator.comparingDouble(Range::startM));

        double covered = 0d;
        double currentStart = ranges.get(0).startM();
        double currentEnd = ranges.get(0).endM();
        for (int i = 1; i < ranges.size(); i++) {
            Range range = ranges.get(i);
            if (range.startM() <= currentEnd) {
                currentEnd = Math.max(currentEnd, range.endM());
                continue;
            }
            covered += currentEnd - currentStart;
            currentStart = range.startM();
            currentEnd = range.endM();
        }
        covered += currentEnd - currentStart;

        return (int) Math.floor((covered * 100d) / totalLength);
    }

    private Projection projectOntoRoute(List<RouteSegment> segments, RideLocationRequest tracePoint) {
        Projection best = null;
        for (RouteSegment segment : segments) {
            Projection candidate = projectOntoSegment(segment, tracePoint);
            if (candidate == null) {
                continue;
            }
            if (best == null || candidate.distanceToRouteM() < best.distanceToRouteM()) {
                best = candidate;
            }
        }
        return best != null && best.distanceToRouteM() <= OFF_ROUTE_RECOVERY_THRESHOLD_M ? best : null;
    }

    private Projection projectOntoSegment(RouteSegment segment, RideLocationRequest tracePoint) {
        double pointLat = tracePoint.lat().doubleValue();
        double pointLon = tracePoint.lon().doubleValue();
        double startLat = segment.start().getLatitude().doubleValue();
        double startLon = segment.start().getLongitude().doubleValue();
        double endLat = segment.end().getLatitude().doubleValue();
        double endLon = segment.end().getLongitude().doubleValue();

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
            double distance = Math.hypot(px - x1, py - y1);
            return new Projection(segment.cumulativeStartM(), distance);
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        double normalizedT = Math.max(0d, Math.min(1d, t));
        double closestX = x1 + normalizedT * dx;
        double closestY = y1 + normalizedT * dy;
        double distance = Math.hypot(px - closestX, py - closestY);
        return new Projection(segment.cumulativeStartM() + (segment.lengthM() * normalizedT), distance);
    }

    private List<RouteSegment> buildSegments(List<CourseRoutePointEntity> routePoints) {
        List<RouteSegment> segments = new ArrayList<>();
        double cumulative = 0d;
        for (int i = 0; i < routePoints.size() - 1; i++) {
            CourseRoutePointEntity start = routePoints.get(i);
            CourseRoutePointEntity end = routePoints.get(i + 1);
            double length = distanceMeters(
                    start.getLatitude().doubleValue(),
                    start.getLongitude().doubleValue(),
                    end.getLatitude().doubleValue(),
                    end.getLongitude().doubleValue()
            ).doubleValue();
            segments.add(new RouteSegment(start, end, cumulative, length));
            cumulative += length;
        }
        return segments;
    }

    private double totalLength(List<RouteSegment> segments) {
        return segments.stream().mapToDouble(RouteSegment::lengthM).sum();
    }

    private boolean isLoopCourse(List<CourseRoutePointEntity> routePoints) {
        CourseRoutePointEntity start = routePoints.get(0);
        CourseRoutePointEntity end = routePoints.get(routePoints.size() - 1);
        return distanceMeters(
                start.getLatitude().doubleValue(),
                start.getLongitude().doubleValue(),
                end.getLatitude().doubleValue(),
                end.getLongitude().doubleValue()
        ).intValue() <= LOOP_DETECTION_THRESHOLD_M;
    }

    private int distanceFromStart(CourseEntity course, List<CourseRoutePointEntity> routePoints, RideLocationRequest location) {
        BigDecimal distance = startPointDistanceMeters(course, location);
        if (distance != null) {
            return distance.intValue();
        }
        CourseRoutePointEntity start = routePoints.get(0);
        return distanceMeters(
                location.lat().doubleValue(),
                location.lon().doubleValue(),
                start.getLatitude().doubleValue(),
                start.getLongitude().doubleValue()
        ).intValue();
    }

    private int distanceFromEnd(List<CourseRoutePointEntity> routePoints, RideLocationRequest location) {
        CourseRoutePointEntity end = routePoints.get(routePoints.size() - 1);
        return distanceMeters(
                location.lat().doubleValue(),
                location.lon().doubleValue(),
                end.getLatitude().doubleValue(),
                end.getLongitude().doubleValue()
        ).intValue();
    }

    private boolean hasLeftStartZone(CourseEntity course, List<CourseRoutePointEntity> routePoints, List<RideLocationRequest> trace) {
        for (RideLocationRequest tracePoint : trace) {
            if (distanceFromStart(course, routePoints, tracePoint) > LOOP_START_EXIT_THRESHOLD_M) {
                return true;
            }
        }
        return false;
    }

    private String overallState(RidePolicyOffRouteResponse offRoute, RidePolicyCompletionResponse completion) {
        if ("ELIGIBLE".equals(completion.status())) {
            return "ACTIVE_COMPLETION_READY";
        }
        if ("WARNING".equals(offRoute.status())) {
            return "ACTIVE_WARNING";
        }
        if ("CANDIDATE".equals(offRoute.status())) {
            return "ACTIVE_OFF_ROUTE_CANDIDATE";
        }
        return "ACTIVE_ON_ROUTE";
    }

    private String defaultMessage(RidePolicyOffRouteResponse offRoute, RidePolicyCompletionResponse completion) {
        if ("ELIGIBLE".equals(completion.status())) {
            return completion.loopCourse() != null && completion.loopCourse()
                    ? "출발점으로 복귀했고 주행 커버리지가 충족되어 완주 후보가 되었습니다."
                    : "종점 근처까지 도달했고 주행 커버리지가 충족되어 완주 후보가 되었습니다.";
        }
        if ("WARNING".equals(offRoute.status())) {
            return "경로를 벗어났습니다. 코스 라인으로 복귀하세요.";
        }
        if ("CANDIDATE".equals(offRoute.status())) {
            return "경로 이탈 후보 상태입니다. 코스 라인과의 거리를 줄여 주세요.";
        }
        return "현재 코스를 따라 주행 중입니다.";
    }

    private boolean isLowAccuracy(String phase, RideLocationRequest location) {
        return location.accuracyM().compareTo(accuracyThreshold(phase)) > 0;
    }

    private boolean isStale(String phase, RideLocationRequest location) {
        long age = Duration.between(location.capturedAt().toInstant(), clock.instant()).getSeconds();
        return age > staleThresholdSeconds(phase);
    }

    private BigDecimal accuracyThreshold(String phase) {
        return "ACTIVE".equals(phase) ? ACTIVE_ACCURACY_THRESHOLD_M : PRE_START_ACCURACY_THRESHOLD_M;
    }

    private long staleThresholdSeconds(String phase) {
        return "ACTIVE".equals(phase) ? ACTIVE_STALE_THRESHOLD_SECONDS : PRE_START_STALE_THRESHOLD_SECONDS;
    }

    private BigDecimal startPointDistanceMeters(CourseEntity course, RideLocationRequest location) {
        if (course.getStartLatitude() == null || course.getStartLongitude() == null) {
            return null;
        }
        return distanceMeters(
                location.lat().doubleValue(),
                location.lon().doubleValue(),
                course.getStartLatitude().doubleValue(),
                course.getStartLongitude().doubleValue()
        );
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
                : "현재 위치 정확도가 낮아 주행 정책을 판단하기 어렵습니다.";
    }

    private String staleMessage(String phase) {
        return "PRE_START".equals(phase)
                ? "위치 정보가 오래되어 시작 가능 여부를 판단하기 어렵습니다."
                : "위치 정보가 오래되어 주행 정책을 판단하기 어렵습니다.";
    }

    private enum OffRouteState {
        ON_ROUTE,
        CANDIDATE,
        WARNING
    }

    private record OffRouteDecision(RidePolicyOffRouteResponse response) {
    }

    private record CompletionDecision(RidePolicyCompletionResponse response) {
    }

    private record RouteSegment(
            CourseRoutePointEntity start,
            CourseRoutePointEntity end,
            double cumulativeStartM,
            double lengthM
    ) {
    }

    private record Projection(double distanceAlongRouteM, double distanceToRouteM) {
    }

    private record Range(double startM, double endM) {
    }
}
