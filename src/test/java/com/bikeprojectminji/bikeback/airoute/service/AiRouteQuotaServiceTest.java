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
                2
        );

        quotaService.checkAllowed("1");
        quotaService.checkAllowed("1");

        assertThatThrownBy(() -> quotaService.checkAllowed("1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("AI 경로 추천 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
