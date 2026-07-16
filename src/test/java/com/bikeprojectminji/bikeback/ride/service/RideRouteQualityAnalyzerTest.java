package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRouteQualityStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RideRouteQualityAnalyzerTest {

    private final RideRouteQualityAnalyzer analyzer = new RideRouteQualityAnalyzer();

    @Test
    @DisplayName("정상 GPS trace는 전체 구간과 서버 계산 거리를 FULL로 반환한다")
    void analyzeReturnsFullForCoherentTrace() {
        RideRouteQualityResult result = analyzer.analyze(List.of(
                point(1, 37.56650, 126.97800, 0, 5),
                point(2, 37.56655, 126.97805, 2, 5),
                point(3, 37.56660, 126.97810, 4, 5)
        ));

        assertThat(result.status()).isEqualTo(RideRouteQualityStatus.FULL);
        assertThat(result.selectedSegment()).hasSize(3);
        assertThat(result.distanceM()).isBetween(10, 20);
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    @DisplayName("GPS 튐 뒤 세 점이 일관되면 새 구간으로 인정하고 튄 거리는 합산하지 않는다")
    void analyzeAcceptsNewClusterAfterThreeCoherentPoints() {
        RideRouteQualityResult result = analyzer.analyze(List.of(
                point(1, 37.56650, 126.97800, 0, 5),
                point(2, 37.56655, 126.97805, 2, 5),
                point(3, 37.56660, 126.97810, 4, 5),
                point(4, 37.95000, 127.40000, 5, 5),
                point(5, 37.56665, 126.97815, 6, 5),
                point(6, 37.56670, 126.97820, 8, 5),
                point(7, 37.56675, 126.97825, 10, 5)
        ));

        assertThat(result.status()).isEqualTo(RideRouteQualityStatus.PARTIAL);
        assertThat(result.distanceM()).isLessThan(50);
        assertThat(result.reasons()).contains("IMPLAUSIBLE_SPEED", "MULTIPLE_SEGMENTS");
    }

    @Test
    @DisplayName("GPS 튐 뒤 두 점뿐인 후보 구간은 최종 경로에 연결하지 않는다")
    void analyzeRejectsNewClusterWithFewerThanThreePoints() {
        RideRouteQualityResult result = analyzer.analyze(List.of(
                point(1, 37.56650, 126.97800, 0, 5),
                point(2, 37.56655, 126.97805, 2, 5),
                point(3, 37.56660, 126.97810, 4, 5),
                point(4, 37.95000, 127.40000, 5, 5),
                point(5, 37.56665, 126.97815, 6, 5),
                point(6, 37.56670, 126.97820, 8, 5)
        ));

        assertThat(result.status()).isEqualTo(RideRouteQualityStatus.PARTIAL);
        assertThat(result.selectedSegment()).extracting(RideRecordPointRequest::pointOrder)
                .containsExactly(1, 2, 3);
        assertThat(result.reasons()).contains("INSUFFICIENT_CLUSTER_POINTS");
    }

    @Test
    @DisplayName("정확도 50m는 포함하고 50m 초과 포인트는 원본만 남긴다")
    void analyzeUsesInclusiveAccuracyBoundary() {
        RideRouteQualityResult result = analyzer.analyze(List.of(
                point(1, 37.56650, 126.97800, 0, 50),
                point(2, 37.56655, 126.97805, 2, 50),
                point(3, 37.56660, 126.97810, 4, 50.01),
                point(4, 37.56665, 126.97815, 6, 5),
                point(5, 37.56670, 126.97820, 8, 5),
                point(6, 37.56675, 126.97825, 10, 5)
        ));

        assertThat(result.status()).isEqualTo(RideRouteQualityStatus.PARTIAL);
        assertThat(result.reasons()).contains("LOW_ACCURACY");
        assertThat(result.distanceM()).isGreaterThan(0);
    }

    @Test
    @DisplayName("telemetry가 전혀 없는 기존 요청은 품질 검증을 우회하지 않고 REJECTED 처리한다")
    void analyzeRejectsLegacyCoordinatesWithoutTelemetry() {
        RideRouteQualityResult result = analyzer.analyze(List.of(
                new RideRecordPointRequest(1, bd(37.56650), bd(126.97800)),
                new RideRecordPointRequest(2, bd(37.56655), bd(126.97805)),
                new RideRecordPointRequest(3, bd(37.56660), bd(126.97810))
        ));

        assertThat(result.status()).isEqualTo(RideRouteQualityStatus.REJECTED);
        assertThat(result.selectedSegment()).isEmpty();
        assertThat(result.distanceM()).isZero();
        assertThat(result.reasons()).containsExactlyInAnyOrder("MISSING_TELEMETRY", "NO_USABLE_SEGMENT");
    }

    @Test
    @DisplayName("유효한 두 점 구간이 하나도 없으면 REJECTED와 거리 0을 반환한다")
    void analyzeRejectsTraceWithoutUsableSegment() {
        RideRouteQualityResult result = analyzer.analyze(List.of(
                point(1, 37.56650, 126.97800, 0, 80),
                point(2, 37.95000, 127.40000, 1, 80)
        ));

        assertThat(result.status()).isEqualTo(RideRouteQualityStatus.REJECTED);
        assertThat(result.selectedSegment()).isEmpty();
        assertThat(result.distanceM()).isZero();
        assertThat(result.reasons()).contains("LOW_ACCURACY", "NO_USABLE_SEGMENT");
    }

    private RideRecordPointRequest point(
            int order,
            double latitude,
            double longitude,
            long seconds,
            double accuracyM
    ) {
        return new RideRecordPointRequest(
                order,
                bd(latitude),
                bd(longitude),
                OffsetDateTime.parse("2026-07-16T00:00:00Z").plusSeconds(seconds),
                bd(accuracyM),
                null,
                null,
                null,
                null,
                null
        );
    }

    private BigDecimal bd(double value) {
        return BigDecimal.valueOf(value);
    }
}
