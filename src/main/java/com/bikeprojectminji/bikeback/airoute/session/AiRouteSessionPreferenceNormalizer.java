package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import java.util.List;

final class AiRouteSessionPreferenceNormalizer {

    private AiRouteSessionPreferenceNormalizer() {
    }

    static AiRoutePlanRequest normalize(AiRoutePlanRequest request) {
        String text = request.textIntent() == null ? "" : request.textIntent().trim();
        return new AiRoutePlanRequest(
                request.lat(),
                request.lon(),
                request.destinationLat(),
                request.destinationLon(),
                request.destinationLabel(),
                firstNonBlank(request.rideStyle(), rideStyleFrom(text)),
                firstNonBlank(request.elevationPreference(), elevationFrom(text)),
                request.textIntent()
        );
    }

    private static String rideStyleFrom(String text) {
        if (containsAny(text, List.of("자전거도로", "자전거 도로", "안전"))) {
            return "BIKE_PATH_FIRST";
        }
        if (containsAny(text, List.of("한강", "강변", "풍경", "경치"))) {
            return "SCENERY_FIRST";
        }
        if (containsAny(text, List.of("최단", "빠른", "짧게"))) {
            return "SHORTEST";
        }
        return "BALANCED";
    }

    private static String elevationFrom(String text) {
        if (containsAny(text, List.of("평지", "완만", "쉬운", "편한"))) {
            return "FLAT_FIRST";
        }
        if (containsAny(text, List.of("업힐", "오르막", "등반"))) {
            return "CLIMB_FIRST";
        }
        return "BALANCED_ELEVATION";
    }

    private static String firstNonBlank(String explicit, String fallback) {
        return explicit == null || explicit.isBlank() ? fallback : explicit.trim();
    }

    private static boolean containsAny(String text, List<String> candidates) {
        return candidates.stream().anyMatch(text::contains);
    }
}
