package com.bikeprojectminji.bikeback.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestPathSanitizerTest {

    @Test
    @DisplayName("제거된 URL 기반 영수증 경로가 유입돼도 clientRideId를 마스킹한다")
    void masksLegacyClientRideIdPathSegment() {
        String rawClientRideId = "android-ride-private-001";

        String sanitized = RequestPathSanitizer.sanitize(
                "/api/v1/ride-records/by-client-ride-id/" + rawClientRideId
        );

        assertThat(sanitized)
                .isEqualTo("/api/v1/ride-records/by-client-ride-id/{clientRideId}")
                .doesNotContain(rawClientRideId);
    }

    @Test
    @DisplayName("현재 body 기반 영수증 조회 경로에는 clientRideId가 포함되지 않는다")
    void preservesCurrentReceiptPathWithoutIdentifier() {
        assertThat(RequestPathSanitizer.sanitize("/api/v1/ride-records/receipt"))
                .isEqualTo("/api/v1/ride-records/receipt");
    }

    @Test
    @DisplayName("일반 API 경로는 관측 가치를 유지하도록 그대로 남긴다")
    void preservesOrdinaryRequestPath() {
        assertThat(RequestPathSanitizer.sanitize("/api/v1/courses/1001/route-points"))
                .isEqualTo("/api/v1/courses/1001/route-points");
    }
}
