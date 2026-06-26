package com.bikeprojectminji.bikeback.party.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RidePartyReportEntityTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("파티 신고는 신고자와 신고 사유를 필수 값으로 생성한다")
    void createRequiresReporterAndReason() {
        RidePartyReportEntity report = RidePartyReportEntity.create(20L, 2L, RidePartyReportReason.SPAM_OR_COMMERCIAL, FIXED_CLOCK);

        assertThat(report.getPartyId()).isEqualTo(20L);
        assertThat(report.getReporterUserId()).isEqualTo(2L);
        assertThat(report.getReason()).isEqualTo(RidePartyReportReason.SPAM_OR_COMMERCIAL);
        assertThat(report.getReportedAt()).isEqualTo(Instant.parse("2026-06-26T00:00:00Z").atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("파티 신고는 신고 사유가 없으면 생성할 수 없다")
    void createRejectsMissingReason() {
        assertThatThrownBy(() -> RidePartyReportEntity.create(20L, 2L, null, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason은 비어 있을 수 없습니다.");
    }
}
