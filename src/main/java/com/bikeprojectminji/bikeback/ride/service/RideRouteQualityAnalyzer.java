package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRouteQualityStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RideRouteQualityAnalyzer {

    private static final BigDecimal MAX_ACCURACY_M = new BigDecimal("50.00");
    private static final long MAX_POINT_GAP_MILLIS = 120_000L;
    private static final double MAX_IMPLIED_SPEED_MPS = 25.0;
    private static final int MIN_INITIAL_SEGMENT_POINTS = 2;
    private static final int MIN_RECOVERY_CLUSTER_POINTS = 3;
    private static final double EARTH_RADIUS_M = 6_371_000.0;

    public RideRouteQualityResult analyze(List<RideRecordPointRequest> rawPoints) {
        if (rawPoints.isEmpty()) {
            return rejected(Set.of("NO_USABLE_SEGMENT"));
        }
        if (rawPoints.stream().allMatch(point -> point.capturedAt() == null)) {
            return rejected(Set.of("MISSING_TELEMETRY", "NO_USABLE_SEGMENT"));
        }

        Set<String> reasons = new LinkedHashSet<>();
        List<AcceptedSegment> acceptedSegments = new ArrayList<>();
        List<RideRecordPointRequest> current = new ArrayList<>();
        boolean recoveryCluster = false;

        for (RideRecordPointRequest point : rawPoints) {
            String pointFailure = pointFailure(point);
            if (pointFailure != null) {
                reasons.add(pointFailure);
                acceptEligibleSegment(current, recoveryCluster, acceptedSegments, reasons);
                current = new ArrayList<>();
                recoveryCluster = true;
                continue;
            }
            if (current.isEmpty()) {
                current.add(point);
                continue;
            }

            String linkFailure = linkFailure(current.get(current.size() - 1), point);
            if (linkFailure == null) {
                current.add(point);
                continue;
            }
            reasons.add(linkFailure);
            acceptEligibleSegment(current, recoveryCluster, acceptedSegments, reasons);
            current = new ArrayList<>();
            current.add(point);
            recoveryCluster = true;
        }
        acceptEligibleSegment(current, recoveryCluster, acceptedSegments, reasons);

        if (acceptedSegments.isEmpty()) {
            reasons.add("NO_USABLE_SEGMENT");
            return rejected(reasons);
        }
        if (acceptedSegments.size() > 1) {
            reasons.add("MULTIPLE_SEGMENTS");
        }

        AcceptedSegment selected = acceptedSegments.stream()
                .max(Comparator.comparingDouble(AcceptedSegment::distanceM)
                        .thenComparingInt(segment -> segment.points().size()))
                .orElseThrow();
        int distanceM = (int) Math.round(acceptedSegments.stream().mapToDouble(AcceptedSegment::distanceM).sum());
        RideRouteQualityStatus status = reasons.isEmpty()
                ? RideRouteQualityStatus.FULL
                : RideRouteQualityStatus.PARTIAL;
        return new RideRouteQualityResult(
                List.copyOf(selected.points()),
                distanceM,
                status,
                List.copyOf(reasons)
        );
    }

    private RideRouteQualityResult rejected(Set<String> reasons) {
        return new RideRouteQualityResult(
                List.of(),
                0,
                RideRouteQualityStatus.REJECTED,
                List.copyOf(reasons)
        );
    }

    private void acceptEligibleSegment(
            List<RideRecordPointRequest> points,
            boolean recoveryCluster,
            List<AcceptedSegment> acceptedSegments,
            Set<String> reasons
    ) {
        if (points.isEmpty()) {
            return;
        }
        int requiredPoints = recoveryCluster ? MIN_RECOVERY_CLUSTER_POINTS : MIN_INITIAL_SEGMENT_POINTS;
        if (points.size() < requiredPoints) {
            reasons.add("INSUFFICIENT_CLUSTER_POINTS");
            return;
        }
        acceptedSegments.add(new AcceptedSegment(List.copyOf(points), segmentDistanceM(points)));
    }

    private String pointFailure(RideRecordPointRequest point) {
        if (point.capturedAt() == null) {
            return "MISSING_TIMESTAMP";
        }
        if (point.accuracyM() == null) {
            return "MISSING_ACCURACY";
        }
        if (point.accuracyM().compareTo(MAX_ACCURACY_M) > 0) {
            return "LOW_ACCURACY";
        }
        return null;
    }

    private String linkFailure(RideRecordPointRequest previous, RideRecordPointRequest current) {
        long elapsedMillis = Duration.between(previous.capturedAt(), current.capturedAt()).toMillis();
        if (elapsedMillis <= 0L) {
            return "NON_MONOTONIC_TIME";
        }
        if (elapsedMillis > MAX_POINT_GAP_MILLIS) {
            return "TIME_GAP";
        }
        double speedMps = distanceM(previous, current) / (elapsedMillis / 1_000.0);
        return speedMps > MAX_IMPLIED_SPEED_MPS ? "IMPLAUSIBLE_SPEED" : null;
    }

    private double segmentDistanceM(List<RideRecordPointRequest> points) {
        double distanceM = 0.0;
        for (int index = 1; index < points.size(); index++) {
            distanceM += distanceM(points.get(index - 1), points.get(index));
        }
        return distanceM;
    }

    private double distanceM(RideRecordPointRequest start, RideRecordPointRequest end) {
        double startLatitude = Math.toRadians(start.latitude().doubleValue());
        double endLatitude = Math.toRadians(end.latitude().doubleValue());
        double latitudeDelta = endLatitude - startLatitude;
        double longitudeDelta = Math.toRadians(end.longitude().doubleValue() - start.longitude().doubleValue());
        double haversine = Math.sin(latitudeDelta / 2.0) * Math.sin(latitudeDelta / 2.0)
                + Math.cos(startLatitude) * Math.cos(endLatitude)
                * Math.sin(longitudeDelta / 2.0) * Math.sin(longitudeDelta / 2.0);
        return EARTH_RADIUS_M * 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
    }

    private record AcceptedSegment(List<RideRecordPointRequest> points, double distanceM) {
    }
}
