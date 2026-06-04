package com.bikeprojectminji.bikeback.routing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeBicycleRoutingClientTest {

    private final FakeBicycleRoutingClient client = new FakeBicycleRoutingClient();

    @Test
    @DisplayName("fake 자전거 경로 provider는 추천/경치/자전거도로 후보를 반환한다")
    void routeReturnsThreeTravelCandidates() {
        BicycleRoutingProviderResult result = client.route(new BicycleRouteRequest(
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                BigDecimal.valueOf(37.6026),
                BigDecimal.valueOf(126.9803),
                "SCENERY_FIRST"
        ));

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.provider()).isEqualTo("FAKE");
        assertThat(result.candidates())
                .extracting(candidate -> candidate.routeType())
                .containsExactly("RECOMMENDED", "SCENIC", "BIKE_PATH");
        assertThat(result.candidates().get(0).polyline()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.candidates().get(2).bikePathScore()).isGreaterThan(result.candidates().get(0).bikePathScore());
    }
}
