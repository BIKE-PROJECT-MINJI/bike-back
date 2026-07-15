package com.bikeprojectminji.bikeback.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestPathSanitizerTest {

    @Test
    @DisplayName("clientRideId 영수증 조회 경로는 식별자 구간만 템플릿으로 치환한다")
    void masksClientRideIdPathSegment() {
        String rawClientRideId = "android-ride-private-001";

        String sanitized = RequestPathSanitizer.sanitize(
                "/api/v1/ride-records/by-client-ride-id/" + rawClientRideId
        );

        assertThat(sanitized)
                .isEqualTo("/api/v1/ride-records/by-client-ride-id/{clientRideId}")
                .doesNotContain(rawClientRideId);
    }

    @Test
    @DisplayName("일반 API 경로는 관측 가치를 유지하도록 그대로 남긴다")
    void preservesOrdinaryRequestPath() {
        assertThat(RequestPathSanitizer.sanitize("/api/v1/courses/1001/route-points"))
                .isEqualTo("/api/v1/courses/1001/route-points");
    }
}
