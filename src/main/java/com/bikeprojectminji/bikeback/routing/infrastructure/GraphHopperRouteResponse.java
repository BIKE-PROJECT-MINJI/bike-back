package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePoint;
import com.bikeprojectminji.bikeback.routing.service.ElevationSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

record GraphHopperRouteResponse(List<GraphHopperPath> paths) {
}

record GraphHopperPath(
        double distance,
        long time,
        BigDecimal ascend,
        BigDecimal descend,
        GraphHopperLineString points,
        Map<String, List<List<Object>>> details
) {

    BicycleRouteCandidate toCandidate(String preference, GraphHopperRouteEvidenceMapper evidenceMapper) {
        List<BicycleRoutePoint> routePoints = points == null ? List.of() : points.toRoutePoints();
        ElevationSummary elevationSummary = toElevationSummary(routePoints);
        GraphHopperRouteEvidence evidence = evidenceMapper.map(details, elevationSummary);
        return new BicycleRouteCandidate(
                routeTypeFor(preference),
                BigDecimal.valueOf(distance).setScale(0, RoundingMode.HALF_UP).intValue(),
                BigDecimal.valueOf(time).divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).intValue(),
                routePoints,
                evidence.summary(),
                evidence.bikePathScore(),
                evidence.sceneryScore(),
                evidence.badges(),
                evidence.elevationSummary()
        );
    }

    private ElevationSummary toElevationSummary(List<BicycleRoutePoint> routePoints) {
        List<BigDecimal> altitudes = routePoints.stream()
                .map(BicycleRoutePoint::altitudeM)
                .filter(altitude -> altitude != null)
                .toList();
        return new ElevationSummary(
                ascend,
                descend,
                altitudes.stream().min(BigDecimal::compareTo).orElse(null),
                altitudes.stream().max(BigDecimal::compareTo).orElse(null),
                maxSlope(),
                averageSlope()
        );
    }

    private BigDecimal maxSlope() {
        return slope("max_slope", true);
    }

    private BigDecimal averageSlope() {
        return slope("average_slope", false);
    }

    private BigDecimal slope(String key, boolean max) {
        if (details == null || details.get(key) == null) {
            return null;
        }
        List<BigDecimal> values = new ArrayList<>();
        for (List<Object> segment : details.get(key)) {
            slopeValue(segment).ifPresent(values::add);
        }
        if (values.isEmpty()) {
            return null;
        }
        if (max) {
            return values.stream().max(BigDecimal::compareTo).orElse(null);
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private Optional<BigDecimal> slopeValue(List<Object> segment) {
        if (segment == null || segment.size() < 3 || segment.get(2) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(String.valueOf(segment.get(2))).abs());
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private String routeTypeFor(String preference) {
        if ("SCENERY_FIRST".equals(preference)) {
            return "SCENIC";
        }
        if ("BIKE_PATH_FIRST".equals(preference)) {
            return "BIKE_PATH";
        }
        return "RECOMMENDED";
    }
}

record GraphHopperLineString(String type, List<List<BigDecimal>> coordinates) {

    List<BicycleRoutePoint> toRoutePoints() {
        if (!"LineString".equals(type) || coordinates == null) {
            return List.of();
        }
        List<BicycleRoutePoint> routePoints = new ArrayList<>();
        for (List<BigDecimal> coordinate : coordinates) {
            if (coordinate != null && coordinate.size() >= 2 && coordinate.get(0) != null && coordinate.get(1) != null) {
                BigDecimal altitudeM = coordinate.size() >= 3 ? coordinate.get(2) : null;
                routePoints.add(new BicycleRoutePoint(coordinate.get(1), coordinate.get(0), "GraphHopper", altitudeM));
            }
        }
        return routePoints;
    }
}
