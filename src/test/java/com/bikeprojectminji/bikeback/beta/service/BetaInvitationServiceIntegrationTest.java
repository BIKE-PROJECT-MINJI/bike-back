package com.bikeprojectminji.bikeback.beta.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.beta.entity.BetaInvitationCodeEntity;
import com.bikeprojectminji.bikeback.beta.repository.BetaInvitationCodeRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BetaInvitationServiceIntegrationTest {

    @Autowired
    private BetaInvitationService betaInvitationService;

    @Autowired
    private BetaInvitationCodeRepository betaInvitationCodeRepository;

    @Autowired
    private Clock clock;

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("초대 코드 검증은 사용 가능 코드의 만료 시각을 반환한다")
    void verifyReturnsExpiryWhenCodeIsUsable() {
        BetaInvitationCodeEntity code = new BetaInvitationCodeEntity(
                "BIKE-2026",
                Instant.parse("2026-06-22T00:00:00Z"),
                clock
        );
        betaInvitationCodeRepository.save(code);

        assertThat(betaInvitationService.verify("BIKE-2026").valid()).isTrue();
        assertThat(betaInvitationService.verify("BIKE-2026").expiresAt())
                .isEqualTo(Instant.parse("2026-06-22T00:00:00Z"));
    }

    @Test
    @DisplayName("초대 코드 사용은 코드에 사용자와 사용 시각을 기록한다")
    void consumeMarksInvitationAsUsed() {
        BetaInvitationCodeEntity code = new BetaInvitationCodeEntity(
                "BIKE-USE",
                Instant.parse("2026-06-22T00:00:00Z"),
                clock
        );
        betaInvitationCodeRepository.save(code);

        betaInvitationService.consumeForUser("BIKE-USE", 7L);

        BetaInvitationCodeEntity usedCode = betaInvitationCodeRepository.findByCode("BIKE-USE").orElseThrow();
        assertThat(usedCode.getUsedByUserId()).isEqualTo(7L);
        assertThat(usedCode.getUsedAt()).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"));
    }

    @Test
    @DisplayName("이미 사용된 초대 코드는 다시 사용할 수 없다")
    void consumeRejectsUsedInvitation() {
        BetaInvitationCodeEntity code = new BetaInvitationCodeEntity(
                "BIKE-USED",
                Instant.parse("2026-06-22T00:00:00Z"),
                Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC)
        );
        code.markUsed(1L, Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC));
        betaInvitationCodeRepository.save(code);

        assertThatThrownBy(() -> betaInvitationService.consumeForUser("BIKE-USED", 7L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("이미 사용된 초대 코드입니다.");
    }

    @Test
    @DisplayName("만료된 초대 코드는 사용할 수 없다")
    void consumeRejectsExpiredInvitation() {
        BetaInvitationCodeEntity code = new BetaInvitationCodeEntity(
                "BIKE-OLD",
                Instant.parse("2026-06-14T00:00:00Z"),
                clock
        );
        betaInvitationCodeRepository.save(code);

        assertThatThrownBy(() -> betaInvitationService.consumeForUser("BIKE-OLD", 7L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("만료된 초대 코드입니다.");
    }
}
