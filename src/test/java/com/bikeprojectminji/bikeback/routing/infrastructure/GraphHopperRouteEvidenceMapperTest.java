package com.bikeprojectminji.bikeback.routing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphHopperRouteEvidenceMapperTest {

    private final GraphHopperRouteEvidenceMapper mapper = new GraphHopperRouteEvidenceMapper();

    @Test
    @DisplayName("GraphHopper path detail은 정규화된 evidence badge와 점수로 변환된다")
    void mapsPathDetailsToEvidenceBadgesAndScores() {
        GraphHopperRouteEvidence evidence = mapper.map(Map.of(
                "road_class", List.of(List.of(0, 2, "cycleway")),
                "bike_network", List.of(List.of(0, 2, "regional")),
                "surface", List.of(List.of(0, 2, "asphalt")),
                "smoothness", List.of(List.of(0, 2, "good")),
                "road_environment", List.of(List.of(1, 2, "bridge"))
        ));

        assertThat(evidence.summary()).contains("cycleway", "regional", "asphalt");
        assertThat(evidence.bikePathScore()).isGreaterThanOrEqualTo(85);
        assertThat(evidence.sceneryScore()).isGreaterThanOrEqualTo(70);
        assertThat(evidence.badges()).extracting("status").contains("VERIFIED", "WARNING");
        assertThat(evidence.badges()).extracting("source")
                .contains("graphhopper.road_class", "graphhopper.surface", "graphhopper.smoothness");
    }

    @Test
    @DisplayName("GraphHopper tag가 없으면 실패가 아니라 UNKNOWN evidence로 변환된다")
    void mapsMissingTagsToUnknownEvidence() {
        GraphHopperRouteEvidence evidence = mapper.map(null);

        assertThat(evidence.summary()).isEqualTo("GraphHopper OSM 자전거 경로 기준");
        assertThat(evidence.bikePathScore()).isEqualTo(70);
        assertThat(evidence.sceneryScore()).isEqualTo(65);
        assertThat(evidence.badges()).hasSize(7);
        assertThat(evidence.badges()).extracting("status").containsOnly("UNKNOWN");
    }

    @Test
    @DisplayName("통행 불가 수준의 smoothness는 FAILED evidence로 변환된다")
    void mapsImpassableSmoothnessToFailedEvidence() {
        GraphHopperRouteEvidence evidence = mapper.map(Map.of(
                "smoothness", List.of(List.of(0, 2, "impassable"))
        ));

        assertThat(evidence.badges())
                .filteredOn(badge -> "graphhopper.smoothness".equals(badge.source()))
                .extracting("status", "severity")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("FAILED", "HIGH"));
    }

    @Test
    @DisplayName("max_slope가 없어도 average_slope 급경사는 WARNING evidence로 변환된다")
    void mapsAverageOnlySteepSlopeToWarningEvidence() {
        GraphHopperRouteEvidence evidence = mapper.map(Map.of(
                "average_slope", List.of(List.of(0, 2, 11.0))
        ));

        assertThat(evidence.badges())
                .filteredOn(badge -> "graphhopper.slope".equals(badge.source()))
                .extracting("status", "severity")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("WARNING", "MEDIUM"));
    }
}
