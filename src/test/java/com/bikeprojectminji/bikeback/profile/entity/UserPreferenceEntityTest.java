package com.bikeprojectminji.bikeback.profile.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserPreferenceEntityTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("선호경로 설정은 사용자와 명시 선호값을 생성 시점과 함께 보관한다")
    void createStoresPreferenceValuesWithTimestamp() {
        UserPreferenceEntity preference = UserPreferenceEntity.create(
                1L,
                true,
                BikeRoadPriority.HIGH,
                true,
                true,
                clock
        );

        assertThat(preference.getUserId()).isEqualTo(1L);
        assertThat(preference.isScenic()).isTrue();
        assertThat(preference.getBikeRoadPriority()).isEqualTo(BikeRoadPriority.HIGH);
        assertThat(preference.isAvoidDust()).isTrue();
        assertThat(preference.isAvoidUnsafeSurface()).isTrue();
        assertThat(preference.getCreatedAt()).isEqualTo(Instant.parse("2026-06-04T00:00:00Z").atOffset(ZoneOffset.UTC));
        assertThat(preference.getUpdatedAt()).isEqualTo(Instant.parse("2026-06-04T00:00:00Z").atOffset(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("선호경로 설정은 자전거도로 우선순수가 없으면 거부한다")
    void createRejectsMissingBikeRoadPriority() {
        assertThatThrownBy(() -> UserPreferenceEntity.create(1L, true, null, false, false, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bikeRoadPriority");
    }

    @Test
    @DisplayName("선호경로 갱신은 선호값과 수정시각만 바꾼다")
    void updateChangesPreferenceValuesAndUpdatedAt() {
        UserPreferenceEntity preference = UserPreferenceEntity.create(
                1L,
                false,
                BikeRoadPriority.LOW,
                false,
                false,
                clock
        );
        Clock later = Clock.fixed(Instant.parse("2026-06-04T01:00:00Z"), ZoneOffset.UTC);

        preference.update(true, BikeRoadPriority.HIGH, true, true, later);

        assertThat(preference.isScenic()).isTrue();
        assertThat(preference.getBikeRoadPriority()).isEqualTo(BikeRoadPriority.HIGH);
        assertThat(preference.isAvoidDust()).isTrue();
        assertThat(preference.isAvoidUnsafeSurface()).isTrue();
        assertThat(preference.getCreatedAt()).isEqualTo(Instant.parse("2026-06-04T00:00:00Z").atOffset(ZoneOffset.UTC));
        assertThat(preference.getUpdatedAt()).isEqualTo(Instant.parse("2026-06-04T01:00:00Z").atOffset(ZoneOffset.UTC));
    }
}
