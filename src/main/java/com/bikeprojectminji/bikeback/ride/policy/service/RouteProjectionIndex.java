package com.bikeprojectminji.bikeback.ride.policy.service;

import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.ride.policy.dto.RideLocationRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class RouteProjectionIndex {

    private static final int LOCAL_WINDOW_RADIUS = 2;

    private final List<RouteSegment> segments;

    public RouteProjectionIndex(List<CourseRoutePointEntity> routePoints) {
        this.segments = buildSegments(routePoints);
    }

    public RouteProjectionMatch findNearest(RideLocationRequest tracePoint, Integer preferredSegmentIndex) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("route segment가 비어 있습니다.");
        }

        RouteProjectionMatch localBest = preferredSegmentIndex != null
                ? findNearestWithinWindow(tracePoint, preferredSegmentIndex)
                : null;

        if (localBest != null && isInsideWindow(preferredSegmentIndex, localBest.segmentIndex())) {
            return localBest;
        }
        return findNearestAcrossAllSegments(tracePoint, preferredSegmentIndex);
    }

    public RouteProjectionMatch project(RideLocationRequest tracePoint, Integer preferredSegmentIndex, double maxDistanceM) {
        RouteProjectionMatch nearest = findNearest(tracePoint, preferredSegmentIndex);
        return nearest.distanceToRouteM() <= maxDistanceM ? nearest : null;
    }

    public RouteProgress progressAt(RideLocationRequest tracePoint, Integer preferredSegmentIndex) {
        RouteProjectionMatch nearest = findNearest(tracePoint, preferredSegmentIndex);
        double totalLengthM = totalLengthM();
        int distanceAlongRouteM = roundedMeters(Math.max(0d, Math.min(totalLengthM, nearest.distanceAlongRouteM())));
        int remainingDistanceM = roundedMeters(Math.max(0d, totalLengthM - distanceAlongRouteM));
        int progressPercent = totalLengthM <= 0d
                ? 0
                : Math.min(100, (int) Math.round((distanceAlongRouteM * 100d) / totalLengthM));
        return new RouteProgress(distanceAlongRouteM, remainingDistanceM, progressPercent, nearest.segmentIndex());
    }

    public List<RouteSegment> segments() {
        return segments;
    }

    private double totalLengthM() {
        return segments.stream().mapToDouble(RouteSegment::lengthM).sum();
    }

    private int roundedMeters(double meters) {
        return BigDecimal.valueOf(meters).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private RouteProjectionMatch findNearestWithinWindow(RideLocationRequest tracePoint, int preferredSegmentIndex) {
        int startIndex = Math.max(0, preferredSegmentIndex - LOCAL_WINDOW_RADIUS);
        int endIndex = Math.min(segments.size() - 1, preferredSegmentIndex + LOCAL_WINDOW_RADIUS);
        return findNearest(tracePoint, startIndex, endIndex);
    }

    private boolean isInsideWindow(int preferredSegmentIndex, int nearestSegmentIndex) {
        return Math.abs(preferredSegmentIndex - nearestSegmentIndex) < LOCAL_WINDOW_RADIUS;
    }

    private RouteProjectionMatch findNearestAcrossAllSegments(RideLocationRequest tracePoint, Integer preferredSegmentIndex) {
        return findNearest(tracePoint, 0, segments.size() - 1, preferredSegmentIndex);
    }

    private RouteProjectionMatch findNearest(RideLocationRequest tracePoint, int startIndex, int endIndex) {
        return findNearest(tracePoint, startIndex, endIndex, null);
    }

    private RouteProjectionMatch findNearest(RideLocationRequest tracePoint, int startIndex, int endIndex, Integer preferredSegmentIndex) {
        RouteProjectionMatch best = null;
        for (int index = startIndex; index <= endIndex; index++) {
            RouteProjectionMatch candidate = projectOntoSegment(index, segments.get(index), tracePoint);
            if (best == null || isBetterCandidate(candidate, best, preferredSegmentIndex)) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean isBetterCandidate(RouteProjectionMatch candidate, RouteProjectionMatch best, Integer preferredSegmentIndex) {
        double distanceDelta = candidate.distanceToRouteM() - best.distanceToRouteM();
        if (Math.abs(distanceDelta) > 0.001d) {
            return distanceDelta < 0d;
        }
        if (preferredSegmentIndex == null) {
            return false;
        }
        if (candidate.segmentIndex() >= preferredSegmentIndex && best.segmentIndex() < preferredSegmentIndex) {
            return true;
        }
        if (candidate.segmentIndex() < preferredSegmentIndex && best.segmentIndex() >= preferredSegmentIndex) {
            return false;
        }
        int candidateDistance = Math.abs(candidate.segmentIndex() - preferredSegmentIndex);
        int bestDistance = Math.abs(best.segmentIndex() - preferredSegmentIndex);
        if (candidateDistance != bestDistance) {
            return candidateDistance < bestDistance;
        }
        return candidate.segmentIndex() > best.segmentIndex();
    }

    private RouteProjectionMatch projectOntoSegment(int segmentIndex, RouteSegment segment, RideLocationRequest tracePoint) {
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
            return new RouteProjectionMatch(segmentIndex, segment.cumulativeStartM(), Math.hypot(px - x1, py - y1));
        }

        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        double normalizedT = Math.max(0d, Math.min(1d, t));
        double closestX = x1 + normalizedT * dx;
        double closestY = y1 + normalizedT * dy;
        return new RouteProjectionMatch(
                segmentIndex,
                segment.cumulativeStartM() + (segment.lengthM() * normalizedT),
                Math.hypot(px - closestX, py - closestY)
        );
    }

    private List<RouteSegment> buildSegments(List<CourseRoutePointEntity> routePoints) {
        List<RouteSegment> built = new ArrayList<>();
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
            built.add(new RouteSegment(start, end, cumulative, length));
            cumulative += length;
        }
        return built;
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

    public record RouteSegment(
            CourseRoutePointEntity start,
            CourseRoutePointEntity end,
            double cumulativeStartM,
            double lengthM
    ) {
    }

    public record RouteProjectionMatch(
            int segmentIndex,
            double distanceAlongRouteM,
            double distanceToRouteM
    ) {
    }

    public record RouteProgress(
            int distanceAlongRouteM,
            int remainingDistanceM,
            int progressPercent,
            int nearestSegmentIndex
    ) {
    }
}
