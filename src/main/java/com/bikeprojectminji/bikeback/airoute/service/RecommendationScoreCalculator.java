package com.bikeprojectminji.bikeback.airoute.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class RecommendationScoreCalculator {

    private static final int FALLBACK_SCENERY = 70;
    private static final int FALLBACK_BIKE_PATH = 70;
    private static final int FALLBACK_SAFETY = 72;
    private static final int FALLBACK_CONDITION_WITH_WEATHER = 72;
    private static final int FALLBACK_CONDITION_WITHOUT_WEATHER = 58;
    private static final int FALLBACK_PREFERENCE_FIT = 75;
    private static final int UNKNOWN_PENALTY_PER_EVIDENCE = 4;
    private static final int UNKNOWN_PENALTY_CAP = 16;

    public RecommendationScore calculateFallback(String routePriority, boolean weatherAvailable, int unknownEvidenceCount) {
        return calculate(
                routePriority,
                FALLBACK_SCENERY,
                FALLBACK_BIKE_PATH,
                FALLBACK_SAFETY,
                weatherAvailable ? FALLBACK_CONDITION_WITH_WEATHER : FALLBACK_CONDITION_WITHOUT_WEATHER,
                FALLBACK_PREFERENCE_FIT,
                0,
                unknownPenalty(unknownEvidenceCount)
        );
    }

    public RecommendationScore calculateWithRouteEvidence(
            String routePriority,
            int scenery,
            int bikePath,
            int warningEvidenceCount,
            int unknownEvidenceCount,
            int distanceMeters
    ) {
        int warnings = Math.max(0, warningEvidenceCount);
        int safety = Math.max(55, 76 - warnings * 8);
        int condition = Math.max(50, 74 - warnings * 10 - Math.max(0, unknownEvidenceCount) * 2);
        int distancePenalty = distanceMeters > 12000
                ? Math.min(12, (distanceMeters - 12000) / 1000)
                : 0;
        return calculate(
                routePriority,
                scenery,
                bikePath,
                safety,
                condition,
                82,
                distancePenalty,
                unknownPenalty(unknownEvidenceCount)
        );
    }

    public RecommendationScore calculate(
            String routePriority,
            int scenery,
            int bikePath,
            int safety,
            int condition,
            int preferenceFit,
            int distancePenalty,
            int unknownPenalty
    ) {
        Weights weights = Weights.from(routePriority);
        int normalizedScenery = clamp(scenery);
        int normalizedBikePath = clamp(bikePath);
        int normalizedSafety = clamp(safety);
        int normalizedCondition = clamp(condition);
        int normalizedPreferenceFit = clamp(preferenceFit);
        int normalizedDistancePenalty = Math.max(0, distancePenalty);
        int normalizedUnknownPenalty = Math.max(0, unknownPenalty);

        BigDecimal weighted = BigDecimal.valueOf(normalizedScenery).multiply(weights.scenery())
                .add(BigDecimal.valueOf(normalizedBikePath).multiply(weights.bikePath()))
                .add(BigDecimal.valueOf(normalizedSafety).multiply(weights.safety()))
                .add(BigDecimal.valueOf(normalizedCondition).multiply(weights.condition()))
                .add(BigDecimal.valueOf(normalizedPreferenceFit).multiply(weights.preferenceFit()));

        int total = weighted.setScale(0, RoundingMode.HALF_UP).intValue()
                - normalizedDistancePenalty
                - normalizedUnknownPenalty;

        return new RecommendationScore(
                clamp(total),
                normalizedScenery,
                normalizedBikePath,
                normalizedSafety,
                normalizedCondition,
                normalizedPreferenceFit,
                normalizedDistancePenalty,
                normalizedUnknownPenalty
        );
    }

    private int unknownPenalty(int unknownEvidenceCount) {
        return Math.min(UNKNOWN_PENALTY_CAP, Math.max(0, unknownEvidenceCount) * UNKNOWN_PENALTY_PER_EVIDENCE);
    }

    private int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 100);
    }

    private record Weights(
            BigDecimal scenery,
            BigDecimal bikePath,
            BigDecimal safety,
            BigDecimal condition,
            BigDecimal preferenceFit
    ) {

        private static Weights from(String routePriority) {
            if ("BIKE_PATH_FIRST".equalsIgnoreCase(routePriority) || "bike_path_first".equalsIgnoreCase(routePriority)) {
                return new Weights(decimal("0.20"), decimal("0.34"), decimal("0.20"), decimal("0.16"), decimal("0.10"));
            }
            if ("BALANCED".equalsIgnoreCase(routePriority) || "balanced".equalsIgnoreCase(routePriority)) {
                return new Weights(decimal("0.24"), decimal("0.26"), decimal("0.22"), decimal("0.18"), decimal("0.10"));
            }
            if ("SAFE_FIRST".equalsIgnoreCase(routePriority) || "safe_first".equalsIgnoreCase(routePriority)) {
                return new Weights(decimal("0.16"), decimal("0.24"), decimal("0.32"), decimal("0.18"), decimal("0.10"));
            }
            return new Weights(decimal("0.30"), decimal("0.22"), decimal("0.20"), decimal("0.18"), decimal("0.10"));
        }

        private static BigDecimal decimal(String value) {
            return new BigDecimal(value);
        }
    }
}
