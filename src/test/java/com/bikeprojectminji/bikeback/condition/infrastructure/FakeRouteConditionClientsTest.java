package com.bikeprojectminji.bikeback.condition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.condition.service.RouteConditionEvidence;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeRouteConditionClientsTest {

    private final RouteConditionRequest request = new RouteConditionRequest(
            BigDecimal.valueOf(37.5665),
            BigDecimal.valueOf(126.9780)
    );

    @Test
    @DisplayName("fake 조건 provider들은 날씨 verified와 조건 unknown evidence를 반환한다")
    void fakeClientsReturnDeterministicEvidence() {
        assertEvidence(new FakeWeatherConditionClient().lookup(request), "weather", "VERIFIED");
        assertEvidence(new FakeDustClient().lookup(request), "dust", "UNKNOWN");
        assertEvidence(new FakeRoadworkClient().lookup(request), "roadwork", "UNKNOWN");
        assertEvidence(new FakeClosureClient().lookup(request), "closure", "UNKNOWN");
        assertEvidence(new FakeSurfaceRiskClient().lookup(request), "surface", "UNKNOWN");
    }

    private void assertEvidence(RouteConditionEvidence evidence, String source, String status) {
        assertThat(evidence.source()).isEqualTo(source);
        assertThat(evidence.status()).isEqualTo(status);
        assertThat(evidence.summary()).isNotBlank();
    }
}
