package com.bikeprojectminji.bikeback.airoute.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRouteSessionPreferenceNormalizerTest {

    @Test
    @DisplayName("서버는 자연어에서 풍경과 평지 선호를 보수적으로 정규화한다")
    void normalizesSceneryAndFlatPreferenceFromText() {
        AiRoutePlanRequest normalized = AiRouteSessionPreferenceNormalizer.normalize(new AiRoutePlanRequest(
                BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0), null, null, "",
                null, null, "평지 위주로 한강 풍경이 보이는 코스"
        ));

        assertThat(normalized.rideStyle()).isEqualTo("SCENERY_FIRST");
        assertThat(normalized.elevationPreference()).isEqualTo("FLAT_FIRST");
    }

    @Test
    @DisplayName("명시한 구조화 선호는 자연어 추론보다 우선한다")
    void preservesExplicitStructuredPreference() {
        AiRoutePlanRequest normalized = AiRouteSessionPreferenceNormalizer.normalize(new AiRoutePlanRequest(
                BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0), null, null, "",
                "SHORTEST", "CLIMB_FIRST", "평지 자전거도로"
        ));

        assertThat(normalized.rideStyle()).isEqualTo("SHORTEST");
        assertThat(normalized.elevationPreference()).isEqualTo("CLIMB_FIRST");
    }
}
