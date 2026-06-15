package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRouteQuotaServiceTest {

    @Test
    @DisplayName("AI 경로 quota는 사용자 subject 기준으로 1분 제한을 초과하면 429 예외를 던진다")
    void checkAllowedRejectsRequestsOverPerMinuteLimit() {
        AiRouteQuotaService quotaService = new AiRouteQuotaService(
                Clock.fixed(Instant.parse("2026-05-27T14:35:00Z"), ZoneOffset.UTC),
                2,
                20,
                3
        );

        quotaService.checkAllowed("1");
        quotaService.checkAllowed("1");

        assertThatThrownBy(() -> quotaService.checkAllowed("1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("AI 경로 추천 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("게스트 AI 경로 quota는 device id 기준으로 하루 3회를 초과하면 429 예외를 던진다")
    void checkGuestAllowedRejectsRequestsOverDailyLimit() {
        AiRouteQuotaService quotaService = new AiRouteQuotaService(
                Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC),
                20,
                20,
                3
        );

        quotaService.checkGuestAllowed("guest-device-1", "127.0.0.1");
        quotaService.checkGuestAllowed("guest-device-1", "127.0.0.1");
        quotaService.checkGuestAllowed("guest-device-1", "127.0.0.1");

        assertThatThrownBy(() -> quotaService.checkGuestAllowed("guest-device-1", "127.0.0.1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("오늘 사용할 수 있는 AI 코스 생성 횟수를 모두 사용했습니다.");
    }

    @Test
    @DisplayName("로그인 사용자 AI 경로 quota는 user subject 기준으로 하루 20회를 초과하면 429 예외를 던진다")
    void checkAuthenticatedAllowedRejectsRequestsOverDailyLimit() {
        AiRouteQuotaService quotaService = new AiRouteQuotaService(
                Clock.fixed(Instant.parse("2026-06-15T10:00:00Z"), ZoneOffset.UTC),
                30,
                2,
                3
        );

        quotaService.checkAuthenticatedAllowed("1");
        quotaService.checkAuthenticatedAllowed("1");

        assertThatThrownBy(() -> quotaService.checkAuthenticatedAllowed("1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("오늘 사용할 수 있는 AI 코스 생성 횟수를 모두 사용했습니다.");
    }
}
