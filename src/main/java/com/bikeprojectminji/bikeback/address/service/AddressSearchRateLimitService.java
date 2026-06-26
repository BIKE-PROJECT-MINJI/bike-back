package com.bikeprojectminji.bikeback.address.service;

import com.bikeprojectminji.bikeback.global.ratelimit.FixedWindowRateLimiter;
import com.bikeprojectminji.bikeback.global.ratelimit.RateLimitKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AddressSearchRateLimitService {

    private static final String LIMIT_MESSAGE = "주소 검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    private final Clock clock;
    private final FixedWindowRateLimiter rateLimiter;
    private final int perMinuteLimit;
    private final int dailyLimit;

    public AddressSearchRateLimitService(
            Clock clock,
            FixedWindowRateLimiter rateLimiter,
            @Value("${address.search.quota.per-minute:30}") int perMinuteLimit,
            @Value("${address.search.quota.per-day:300}") int dailyLimit
    ) {
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.perMinuteLimit = Math.max(1, perMinuteLimit);
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    public void checkAllowed(String ipAddress) {
        String subject = normalizeIp(ipAddress);
        LocalDate today = LocalDate.now(clock);
        rateLimiter.checkAllowed(
                RateLimitKeys.hashed("address-search:minute", subject),
                perMinuteLimit,
                Duration.ofMinutes(1),
                LIMIT_MESSAGE
        );
        rateLimiter.checkAllowed(
                RateLimitKeys.hashed("address-search:daily:" + today, subject),
                dailyLimit,
                ttlUntilTomorrow(),
                LIMIT_MESSAGE
        );
    }

    private String normalizeIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "UNKNOWN";
        }
        return ipAddress.trim();
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
