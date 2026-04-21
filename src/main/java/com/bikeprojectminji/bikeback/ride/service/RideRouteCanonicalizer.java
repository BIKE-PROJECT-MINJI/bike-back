package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RideRouteCanonicalizer {

    private static final double MIN_POINT_DISTANCE_METERS = 5.0;
    private static final double CANONICAL_EPSILON_METERS = 8.0;

    // raw point는 그대로 보관하고, 최종 코스/표시용 경로만 canonical path로 만든다.
    public List<RideRecordPointRequest> canonicalize(List<RideRecordPointRequest> routePoints) {
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

    private void markRamerDouglasPeucker(List<RideRecordPointRequest> routePoints, int startIndex, int endIndex, boolean[] keep) {
        if (endIndex - startIndex <= 1) {
            return;
        }

        double maxDistance = -1.0;
        int maxDistanceIndex = -1;
        for (int i = startIndex + 1; i < endIndex; i++) {
            double perpendicularDistance = perpendicularDistanceMeters(routePoints.get(startIndex), routePoints.get(endIndex), routePoints.get(i));
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

    private double perpendicularDistanceMeters(RideRecordPointRequest start, RideRecordPointRequest end, RideRecordPointRequest candidate) {
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
