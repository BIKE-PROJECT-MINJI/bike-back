package com.bikeprojectminji.bikeback.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CourseReportEntityTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("코스 신고는 신고자와 신고 사유를 필수 값으로 생성한다")
    void createRequiresReporterAndReason() {
        CourseReportEntity report = CourseReportEntity.create(
                10L,
                20L,
                CourseReportReason.PRIVATE_PROPERTY_OR_CLOSED_ROAD,
                FIXED_CLOCK
        );

        assertThat(report.getCourseId()).isEqualTo(10L);
        assertThat(report.getReporterUserId()).isEqualTo(20L);
        assertThat(report.getReason()).isEqualTo(CourseReportReason.PRIVATE_PROPERTY_OR_CLOSED_ROAD);
        assertThat(report.getReportedAt()).isEqualTo(Instant.parse("2026-06-04T00:00:00Z").atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("코스 신고는 신고 사유가 없으면 생성할 수 없다")
    void createRejectsMissingReason() {
        assertThatThrownBy(() -> CourseReportEntity.create(10L, 20L, null, FIXED_CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason은 비어 있을 수 없습니다.");
    }
}
