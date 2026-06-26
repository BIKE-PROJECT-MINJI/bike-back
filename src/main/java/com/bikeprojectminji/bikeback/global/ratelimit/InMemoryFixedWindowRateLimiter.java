package com.bikeprojectminji.bikeback.global.ratelimit;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bike.rate-limit.store", havingValue = "memory")
public class InMemoryFixedWindowRateLimiter implements FixedWindowRateLimiter {

    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public InMemoryFixedWindowRateLimiter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void checkAllowed(String key, int limit, Duration ttl, String limitMessage) {
        int effectiveLimit = Math.max(1, limit);
        Instant now = clock.instant();
        Duration effectiveTtl = normalizeTtl(ttl);
        WindowCounter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return new WindowCounter(now.plus(effectiveTtl), 1);
            }
            return new WindowCounter(current.expiresAt(), current.count() + 1);
        });
        if (counter.count() > effectiveLimit) {
            throw new TooManyRequestsException(limitMessage);
        }
    }

    private Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }

    private record WindowCounter(Instant expiresAt, int count) {
    }
}
