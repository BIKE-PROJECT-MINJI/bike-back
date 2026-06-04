package com.bikeprojectminji.bikeback.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoAuthEntityTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-24T09:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("사용자 엔티티는 계정 삭제 시 삭제 상태와 삭제 시각을 직접 기록한다")
    void userMarksDeletedWithTimestamp() {
        UserEntity user = new UserEntity("kakao:123", "rider@example.com", null, "rider", null);

        user.markDeleted(clock);

        assertThat(user.isDeleted()).isTrue();
        assertThat(user.getAccountStatus()).isEqualTo("DELETED");
        assertThat(user.getDeletedAt()).isEqualTo(OffsetDateTime.parse("2026-05-24T09:00Z"));
    }

    @Test
    @DisplayName("동의 엔티티는 정책 버전 변경 시 버전과 수정 시각을 직접 갱신한다")
    void consentUpdatesPolicyVersionsWithTimestamp() {
        UserConsentEntity consent = new UserConsentEntity(1L, "privacy-v1", "terms-v1", "location-v1", clock);
        Clock updatedClock = Clock.fixed(Instant.parse("2026-05-24T10:00:00Z"), ZoneOffset.UTC);

        consent.updateVersions("privacy-v2", "terms-v2", "location-v2", updatedClock);

        assertThat(consent.getPrivacyPolicyVersion()).isEqualTo("privacy-v2");
        assertThat(consent.getTermsVersion()).isEqualTo("terms-v2");
        assertThat(consent.getLocationTermsVersion()).isEqualTo("location-v2");
        assertThat(consent.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-05-24T10:00Z"));
    }

    @Test
    @DisplayName("카카오 연결 엔티티는 생성 시 연결 시각과 수정 시각을 함께 기록한다")
    void kakaoAccountLinkSetsTimestampsOnCreate() {
        KakaoAccountLinkEntity link = new KakaoAccountLinkEntity(1L, "123456789", clock);

        assertThat(link.getUserId()).isEqualTo(1L);
        assertThat(link.getProviderUserId()).isEqualTo("123456789");
        assertThat(link.getLinkedAt()).isEqualTo(OffsetDateTime.parse("2026-05-24T09:00Z"));
        assertThat(link.getUpdatedAt()).isEqualTo(OffsetDateTime.parse("2026-05-24T09:00Z"));
    }
}
