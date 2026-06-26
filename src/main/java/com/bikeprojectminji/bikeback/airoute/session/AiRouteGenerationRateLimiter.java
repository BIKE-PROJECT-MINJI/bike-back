package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.global.ratelimit.FixedWindowRateLimiter;
import com.bikeprojectminji.bikeback.global.ratelimit.RateLimitKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRouteGenerationRateLimiter {

    private static final String MESSAGE = "AI 코스 생성 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    private final Clock clock;
    private final FixedWindowRateLimiter rateLimiter;
    private final int perMinuteLimit;
    private final int dailyLimit;

    public AiRouteGenerationRateLimiter(
            Clock clock,
            FixedWindowRateLimiter rateLimiter,
            @Value("${ai-route.generation.quota.per-minute:3}") int perMinuteLimit,
            @Value("${ai-route.generation.quota.per-day:30}") int dailyLimit
    ) {
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.perMinuteLimit = perMinuteLimit;
        this.dailyLimit = dailyLimit;
    }

    public void checkAllowed(String userKey) {
        String key = userKey == null ? "anonymous" : userKey;
        LocalDate today = LocalDate.now(clock);
        rateLimiter.checkAllowed(
                RateLimitKeys.hashed("ai-route-generation:minute", key),
                perMinuteLimit,
                Duration.ofMinutes(1),
                MESSAGE
        );
        rateLimiter.checkAllowed(
                RateLimitKeys.hashed("ai-route-generation:daily:" + today, key),
                dailyLimit,
                ttlUntilTomorrow(),
                MESSAGE
        );
    }

    private Duration ttlUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        ZonedDateTime tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(now.getZone());
        Duration ttl = Duration.between(now, tomorrow);
        if (ttl.isZero() || ttl.isNegative()) {
            return Duration.ofDays(1);
        }
        return ttl;
    }
}
