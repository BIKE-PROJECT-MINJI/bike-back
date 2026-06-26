package com.bikeprojectminji.bikeback.airoute.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import com.bikeprojectminji.bikeback.global.ratelimit.InMemoryFixedWindowRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRouteGenerationRateLimiterTest {

    @Test
    @DisplayName("AI 코스 생성 제한은 사용자별 분당 한도를 초과하면 429 예외를 던진다")
    void checkAllowedRejectsWhenPerMinuteLimitExceeded() {
        AiRouteGenerationRateLimiter limiter = new AiRouteGenerationRateLimiter(
                Clock.fixed(Instant.parse("2026-06-12T00:40:00Z"), ZoneOffset.UTC),
                new InMemoryFixedWindowRateLimiter(Clock.fixed(Instant.parse("2026-06-12T00:40:00Z"), ZoneOffset.UTC)),
                2,
                30
        );

        limiter.checkAllowed("1");
        limiter.checkAllowed("1");

        assertThatThrownBy(() -> limiter.checkAllowed("1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("AI 코스 생성 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("AI 코스 생성 제한은 사용자별 일일 한도를 초과하면 429 예외를 던진다")
    void checkAllowedRejectsWhenDailyLimitExceeded() {
        AiRouteGenerationRateLimiter limiter = new AiRouteGenerationRateLimiter(
                Clock.fixed(Instant.parse("2026-06-12T00:40:00Z"), ZoneOffset.UTC),
                new InMemoryFixedWindowRateLimiter(Clock.fixed(Instant.parse("2026-06-12T00:40:00Z"), ZoneOffset.UTC)),
                10,
                2
        );

        limiter.checkAllowed("1");
        limiter.checkAllowed("1");

        assertThatThrownBy(() -> limiter.checkAllowed("1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("AI 코스 생성 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
