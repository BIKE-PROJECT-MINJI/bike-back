package com.bikeprojectminji.bikeback.routing.service;

import java.util.ArrayList;
import java.util.List;

public record BicycleRoutePreference(
        String routePriority,
        String elevationPreference,
        String textIntent
) {

    public static BicycleRoutePreference from(String routePriority, String elevationPreference, String textIntent) {
        return new BicycleRoutePreference(
                normalize(routePriority, "BALANCED"),
                normalize(elevationPreference, null),
                normalize(textIntent, null)
        );
    }

    public String preferenceSummary() {
        List<String> parts = new ArrayList<>();
        parts.add(labelForRoutePriority(routePriority));
        if (elevationPreference != null) {
            parts.add(labelForElevation(elevationPreference));
        }
        if (textIntent != null) {
            parts.add(labelForTextIntent(textIntent));
        }
        return String.join(" + ", parts);
    }

    public String graphHopperCustomModelJson() {
        List<String> priorityRules = new ArrayList<>();
        if ("BIKE_PATH_FIRST".equals(routePriority)) {
            priorityRules.add(rule("road_class == CYCLEWAY", "1.35"));
            priorityRules.add(rule("road_class == PRIMARY", "0.80"));
        }
        if ("SCENERY_FIRST".equals(routePriority) || "TEXT_RIVER_VIEW".equals(textIntent) || "TEXT_FLAT_RIVERSIDE".equals(textIntent)) {
            priorityRules.add(rule("bike_network == LOCAL", "1.12"));
            priorityRules.add(rule("road_environment == BRIDGE", "1.05"));
        }
        if ("FLAT_FIRST".equals(elevationPreference) || "TEXT_FLAT_RIVERSIDE".equals(textIntent)) {
            priorityRules.add(rule("max_slope > 8", "0.65"));
            priorityRules.add(rule("average_slope > 5", "0.75"));
        }
        if ("CLIMB_FIRST".equals(elevationPreference) || "CANONICAL_NAMSAN_NATIONAL_THEATER".equals(textIntent)) {
            priorityRules.add(rule("max_slope > 4", "1.08"));
        }
        if (priorityRules.isEmpty()) {
            return null;
        }
        return """
                {"priority":[%s],"distance_influence":70}
                """.formatted(String.join(",", priorityRules)).trim();
    }

    private static String rule(String condition, String multiplyBy) {
        return """
                {"if":"%s","multiply_by":"%s"}
                """.formatted(condition, multiplyBy).trim();
    }

    private static String labelForRoutePriority(String routePriority) {
        return switch (routePriority) {
            case "SCENERY_FIRST" -> "경치 우선";
            case "BIKE_PATH_FIRST" -> "자전거도로 우선";
            case "SAFE_FIRST" -> "안전 우선";
            default -> "균형형";
        };
    }

    private static String labelForElevation(String elevationPreference) {
        return switch (elevationPreference) {
            case "FLAT_FIRST" -> "평지 우선";
            case "CLIMB_FIRST" -> "업힐 선호";
            case "BALANCED_ELEVATION" -> "고도 균형";
            default -> "고도 조건 " + elevationPreference;
        };
    }

    private static String labelForTextIntent(String textIntent) {
        return switch (textIntent) {
            case "TEXT_RIVER_VIEW" -> "강변 조망";
            case "TEXT_FLAT_RIVERSIDE" -> "평지형 하천";
            case "CANONICAL_NAMSAN_NATIONAL_THEATER" -> "남산 정석 접근";
            default -> "텍스트 조건 " + textIntent;
        };
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toUpperCase();
    }
}
