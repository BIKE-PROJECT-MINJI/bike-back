package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.routing.service.RouteEvidenceBadge;
import com.bikeprojectminji.bikeback.routing.service.ElevationSummary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class GraphHopperRouteEvidenceMapper {

    GraphHopperRouteEvidence map(Map<String, List<List<Object>>> details) {
        return map(details, ElevationSummary.unknown());
    }

    GraphHopperRouteEvidence map(Map<String, List<List<Object>>> details, ElevationSummary elevationSummary) {
        ElevationSummary normalizedElevationSummary = elevationSummary == null ? ElevationSummary.unknown() : elevationSummary;
        List<RouteEvidenceBadge> badges = List.of(
                badgeForRoadClass(values(details, "road_class")),
                badgeForBikeNetwork(values(details, "bike_network")),
                badgeForSurface(values(details, "surface")),
                badgeForSmoothness(values(details, "smoothness")),
                badgeForRoadEnvironment(values(details, "road_environment")),
                badgeForElevation(normalizedElevationSummary),
                badgeForSlope(numberValues(details, "average_slope"), numberValues(details, "max_slope"), normalizedElevationSummary)
        );

        return new GraphHopperRouteEvidence(
                summary(details, normalizedElevationSummary),
                bikePathScore(details),
                sceneryScore(details, normalizedElevationSummary),
                badges,
                normalizedElevationSummary
        );
    }

    private RouteEvidenceBadge badgeForRoadClass(List<String> values) {
        if (values.isEmpty()) {
            return unknown("graphhopper.road_class", "도로 유형", "road_class 태그 미확인");
        }
        if (values.contains("cycleway")) {
            return verified("graphhopper.road_class", "자전거도로", "cycleway 확인");
        }
        return verified("graphhopper.road_class", "도로 유형", "도로 유형: " + String.join(", ", values));
    }

    private RouteEvidenceBadge badgeForBikeNetwork(List<String> values) {
        if (values.isEmpty()) {
            return unknown("graphhopper.bike_network", "자전거 네트워크", "bike_network 태그 미확인");
        }
        return verified("graphhopper.bike_network", "자전거 네트워크", "자전거 네트워크: " + String.join(", ", values));
    }

    private RouteEvidenceBadge badgeForSurface(List<String> values) {
        if (values.isEmpty()) {
            return unknown("graphhopper.surface", "노면", "surface 태그 미확인");
        }
        if (containsAny(values, Set.of("unpaved", "gravel", "dirt", "ground"))) {
            return warning("graphhopper.surface", "노면", "주의 노면: " + String.join(", ", values));
        }
        return verified("graphhopper.surface", "노면", "노면: " + String.join(", ", values));
    }

    private RouteEvidenceBadge badgeForSmoothness(List<String> values) {
        if (values.isEmpty()) {
            return unknown("graphhopper.smoothness", "노면 품질", "smoothness 태그 미확인");
        }
        if (values.contains("impassable")) {
            return failed("graphhopper.smoothness", "노면 품질", "통행 불가 노면 품질: " + String.join(", ", values));
        }
        if (containsAny(values, Set.of("bad", "very_bad", "horrible", "very_horrible"))) {
            return warning("graphhopper.smoothness", "노면 품질", "주의 노면 품질: " + String.join(", ", values));
        }
        return verified("graphhopper.smoothness", "노면 품질", "노면 품질: " + String.join(", ", values));
    }

    private RouteEvidenceBadge badgeForRoadEnvironment(List<String> values) {
        if (values.isEmpty()) {
            return unknown("graphhopper.road_environment", "도로 환경", "road_environment 태그 미확인");
        }
        if (containsAny(values, Set.of("bridge", "tunnel"))) {
            return warning("graphhopper.road_environment", "도로 환경", "교량/터널 구간: " + String.join(", ", values));
        }
        return verified("graphhopper.road_environment", "도로 환경", "도로 환경: " + String.join(", ", values));
    }

    private RouteEvidenceBadge badgeForElevation(ElevationSummary elevationSummary) {
        if (!elevationSummary.hasElevation()) {
            return unknown("graphhopper.elevation", "고도", "GraphHopper 고도 정보 미확인");
        }
        return verified(
                "graphhopper.elevation",
                "고도",
                "상승 " + elevationSummary.totalAscentM() + "m, 하강 " + elevationSummary.totalDescentM() + "m"
        );
    }

    private RouteEvidenceBadge badgeForSlope(List<Double> averageSlopes, List<Double> maxSlopes, ElevationSummary elevationSummary) {
        if (averageSlopes.isEmpty() && maxSlopes.isEmpty() && elevationSummary.maxSlopePercent() == null) {
            return unknown("graphhopper.slope", "경사", "GraphHopper 경사 정보 미확인");
        }
        double maxSlope = elevationSummary.maxSlopePercent() == null
                ? maxAbs(maxSlopes.isEmpty() ? averageSlopes : maxSlopes)
                : elevationSummary.maxSlopePercent().doubleValue();
        if (maxSlope >= 10.0) {
            return warning("graphhopper.slope", "경사", "급경사 구간 최대 " + maxSlope + "%");
        }
        return verified("graphhopper.slope", "경사", "경사 정보 확인");
    }

    private String summary(Map<String, List<List<Object>>> details, ElevationSummary elevationSummary) {
        List<String> values = new ArrayList<>();
        for (String key : List.of("road_class", "bike_network", "surface", "smoothness", "road_environment")) {
            values.addAll(values(details, key));
        }
        if (elevationSummary.hasElevation()) {
            values.add("ascent " + elevationSummary.totalAscentM() + "m");
        }
        if (values.isEmpty()) {
            return "GraphHopper OSM 자전거 경로 기준";
        }
        return "GraphHopper OSM path detail: " + String.join(", ", values);
    }

    private int bikePathScore(Map<String, List<List<Object>>> details) {
        int score = 70;
        if (values(details, "road_class").contains("cycleway")) {
            score += 15;
        }
        if (!values(details, "bike_network").isEmpty()) {
            score += 10;
        }
        List<String> surfaces = values(details, "surface");
        if (surfaces.contains("asphalt") || surfaces.contains("paved")) {
            score += 5;
        }
        return Math.min(100, score);
    }

    private int sceneryScore(Map<String, List<List<Object>>> details, ElevationSummary elevationSummary) {
        int score = 65;
        List<String> environments = values(details, "road_environment");
        if (environments.contains("bridge") || environments.contains("tunnel")) {
            score += 8;
        }
        if (!values(details, "bike_network").isEmpty()) {
            score += 5;
        }
        if (elevationSummary.hasElevation() && elevationSummary.totalAscentM() != null
                && elevationSummary.totalAscentM().doubleValue() >= 80.0) {
            score += 8;
        }
        return Math.min(100, score);
    }

    private RouteEvidenceBadge verified(String source, String label, String summary) {
        return new RouteEvidenceBadge(source, label, "VERIFIED", "INFO", summary);
    }

    private RouteEvidenceBadge warning(String source, String label, String summary) {
        return new RouteEvidenceBadge(source, label, "WARNING", "MEDIUM", summary);
    }

    private RouteEvidenceBadge failed(String source, String label, String summary) {
        return new RouteEvidenceBadge(source, label, "FAILED", "HIGH", summary);
    }

    private RouteEvidenceBadge unknown(String source, String label, String summary) {
        return new RouteEvidenceBadge(source, label, "UNKNOWN", "UNKNOWN", summary);
    }

    private boolean containsAny(List<String> values, Set<String> expectedValues) {
        return values.stream().anyMatch(expectedValues::contains);
    }

    private List<String> values(Map<String, List<List<Object>>> details, String key) {
        if (details == null || details.get(key) == null) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (List<Object> segment : details.get(key)) {
            if (segment != null && segment.size() >= 3 && segment.get(2) != null) {
                String value = String.valueOf(segment.get(2));
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return List.copyOf(values);
    }

    private List<Double> numberValues(Map<String, List<List<Object>>> details, String key) {
        if (details == null || details.get(key) == null) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        for (List<Object> segment : details.get(key)) {
            if (segment != null && segment.size() >= 3 && segment.get(2) != null) {
                try {
                    values.add(Double.parseDouble(String.valueOf(segment.get(2))));
                } catch (NumberFormatException exception) {
                    // GraphHopper detail 값이 숫자가 아니면 해당 구간은 경사 evidence에서 제외한다.
                }
            }
        }
        return List.copyOf(values);
    }

    private double maxAbs(List<Double> values) {
        return values.stream()
                .mapToDouble(Math::abs)
                .max()
                .orElse(0.0);
    }
}
