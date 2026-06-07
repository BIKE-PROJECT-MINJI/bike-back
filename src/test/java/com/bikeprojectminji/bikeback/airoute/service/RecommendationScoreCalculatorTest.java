package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.routing.service.ElevationSummary;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecommendationScoreCalculatorTest {

    private final RecommendationScoreCalculator calculator = new RecommendationScoreCalculator();

    @Test
    @DisplayName("경치 우선 기준은 경치 점수를 자전거도로 점수보다 더 크게 반영한다")
    void sceneryFirstWeightsSceneryMoreThanBikePath() {
        RecommendationScore highScenery = calculator.calculate(
                "SCENERY_FIRST",
                100,
                0,
                70,
                70,
                70,
                0,
                0
        );
        RecommendationScore highBikePath = calculator.calculate(
                "SCENERY_FIRST",
                0,
                100,
                70,
                70,
                70,
                0,
                0
        );

        assertThat(highScenery.total()).isGreaterThan(highBikePath.total());
    }

    @Test
    @DisplayName("unknown evidence가 많으면 unknown penalty가 증가하되 최대 16으로 제한한다")
    void unknownEvidenceIncreasesPenaltyWithCap() {
        RecommendationScore fewUnknowns = calculator.calculateFallback("BALANCED", true, 2);
        RecommendationScore manyUnknowns = calculator.calculateFallback("BALANCED", true, 8);

        assertThat(fewUnknowns.unknownPenalty()).isEqualTo(8);
        assertThat(manyUnknowns.unknownPenalty()).isEqualTo(16);
        assertThat(manyUnknowns.total()).isLessThan(fewUnknowns.total());
    }

    @Test
    @DisplayName("route evidence의 unknown은 score breakdown의 unknown penalty로 반영된다")
    void routeEvidenceUnknownsIncreasePenalty() {
        RecommendationScore verified = calculator.calculateWithRouteEvidence(
                "BIKE_PATH_FIRST",
                78,
                94,
                0,
                0,
                5000
        );
        RecommendationScore unknown = calculator.calculateWithRouteEvidence(
                "BIKE_PATH_FIRST",
                78,
                94,
                0,
                3,
                5000
        );

        assertThat(unknown.unknownPenalty()).isEqualTo(12);
        assertThat(unknown.condition()).isLessThan(verified.condition());
        assertThat(unknown.total()).isLessThan(verified.total());
    }

    @Test
    @DisplayName("route evidence의 warning은 안전과 노면 상태 점수를 낮춘다")
    void routeEvidenceWarningsLowerSafetyAndCondition() {
        RecommendationScore verified = calculator.calculateWithRouteEvidence(
                "SAFE_FIRST",
                78,
                90,
                0,
                0,
                5000
        );
        RecommendationScore warning = calculator.calculateWithRouteEvidence(
                "SAFE_FIRST",
                78,
                90,
                2,
                0,
                5000
        );

        assertThat(warning.safety()).isLessThan(verified.safety());
        assertThat(warning.condition()).isLessThan(verified.condition());
        assertThat(warning.total()).isLessThan(verified.total());
    }

    @Test
    @DisplayName("평지 우선은 상승고도와 최대경사가 낮은 후보의 고도 점수를 높인다")
    void flatFirstPrefersLowAscent() {
        RecommendationScore flat = calculator.calculateWithRouteEvidence(
                "FLAT_FIRST",
                75,
                85,
                0,
                0,
                6000,
                elevation("18", "3")
        );
        RecommendationScore climb = calculator.calculateWithRouteEvidence(
                "FLAT_FIRST",
                75,
                85,
                0,
                0,
                6000,
                elevation("160", "12")
        );

        assertThat(flat.elevation()).isGreaterThan(climb.elevation());
        assertThat(flat.total()).isGreaterThan(climb.total());
    }

    @Test
    @DisplayName("업힐 우선은 상승고도와 최대경사가 높은 후보의 고도 점수를 높인다")
    void climbFirstPrefersHighAscent() {
        RecommendationScore flat = calculator.calculateWithRouteEvidence(
                "CLIMB_FIRST",
                75,
                85,
                0,
                0,
                6000,
                elevation("18", "3")
        );
        RecommendationScore climb = calculator.calculateWithRouteEvidence(
                "CLIMB_FIRST",
                75,
                85,
                0,
                0,
                6000,
                elevation("160", "12")
        );

        assertThat(climb.elevation()).isGreaterThan(flat.elevation());
        assertThat(climb.total()).isGreaterThan(flat.total());
    }

    @Test
    @DisplayName("최종 추천 점수는 0에서 100 사이로 제한한다")
    void totalScoreIsClamped() {
        RecommendationScore high = calculator.calculate(
                "BIKE_PATH_FIRST",
                100,
                100,
                100,
                100,
                100,
                0,
                0
        );
        RecommendationScore low = calculator.calculate(
                "SAFE_FIRST",
                0,
                0,
                0,
                0,
                0,
                100,
                100
        );

        assertThat(high.total()).isEqualTo(100);
        assertThat(low.total()).isZero();
    }

    private ElevationSummary elevation(String totalAscentM, String maxSlopePercent) {
        return new ElevationSummary(
                new BigDecimal(totalAscentM),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(totalAscentM),
                new BigDecimal(maxSlopePercent),
                BigDecimal.ZERO
        );
    }
}
