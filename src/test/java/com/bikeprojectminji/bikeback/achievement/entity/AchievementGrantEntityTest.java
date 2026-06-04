package com.bikeprojectminji.bikeback.achievement.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AchievementGrantEntityTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-04T06:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("성취 지급 엔티티는 지급 타입과 출처, 지급 시각을 생성 시점에 고정한다")
    void createStoresGrantSourceAndGrantedAt() {
        AchievementGrantEntity grant = AchievementGrantEntity.create(
                1L,
                AchievementType.FIRST_COURSE_COMPLETION,
                "global",
                10L,
                20L,
                FIXED_CLOCK
        );

        assertThat(grant.getUserId()).isEqualTo(1L);
        assertThat(grant.getAchievementType()).isEqualTo(AchievementType.FIRST_COURSE_COMPLETION);
        assertThat(grant.getSourceKey()).isEqualTo("global");
        assertThat(grant.getSourceCourseId()).isEqualTo(10L);
        assertThat(grant.getSourceRideRecordId()).isEqualTo(20L);
        assertThat(grant.getGrantedAt()).isEqualTo(Instant.parse("2026-06-04T06:00:00Z").atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("성취 지급 엔티티는 비어 있는 sourceKey를 허용하지 않는다")
    void createRejectsBlankSourceKey() {
        assertThatThrownBy(() -> AchievementGrantEntity.create(
                1L,
                AchievementType.NEW_AREA_VISIT,
                " ",
                10L,
                20L,
                FIXED_CLOCK
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceKey는 비어 있을 수 없습니다.");
    }
}
