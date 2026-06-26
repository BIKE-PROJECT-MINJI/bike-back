package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import com.bikeprojectminji.bikeback.global.ratelimit.FixedWindowRateLimiter;
import com.bikeprojectminji.bikeback.global.ratelimit.RateLimitKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRouteQuotaService {

    private static final String DEFAULT_LIMIT_MESSAGE = "AI 경로 추천 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
    private static final String DAILY_LIMIT_MESSAGE = "오늘 사용할 수 있는 AI 코스 생성 횟수를 모두 사용했습니다.";

    private final Clock clock;
    private final FixedWindowRateLimiter rateLimiter;
    private final int perMinuteLimit;
    private final int authenticatedDailyLimit;
    private final int guestDailyLimit;

    public AiRouteQuotaService(
            Clock clock,
            FixedWindowRateLimiter rateLimiter,
            @Value("${ai-route.quota.per-minute:20}") int perMinuteLimit,
            @Value("${ai-route.quota.authenticated-daily:20}") int authenticatedDailyLimit,
            @Value("${ai-route.quota.guest-daily:3}") int guestDailyLimit
    ) {
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.perMinuteLimit = Math.max(1, perMinuteLimit);
        this.authenticatedDailyLimit = Math.max(1, authenticatedDailyLimit);
        this.guestDailyLimit = Math.max(1, guestDailyLimit);
    }

    public void checkAllowed(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new TooManyRequestsException(DEFAULT_LIMIT_MESSAGE);
        }
        rateLimiter.checkAllowed(
                RateLimitKeys.hashed("ai-route:minute", subject),
                perMinuteLimit,
                Duration.ofMinutes(1),
                DEFAULT_LIMIT_MESSAGE
        );
    }

    public void checkAuthenticatedAllowed(String subject) {
        checkAllowed(subject);
        checkDailyAllowed("USER:" + subject, authenticatedDailyLimit);
    }

    public void checkGuestAllowed(String guestDeviceId, String ipAddress) {
        if (guestDeviceId == null || guestDeviceId.isBlank()) {
            throw new BadRequestException("게스트 device id가 필요합니다.");
        }
        String normalizedIp = normalizeIp(ipAddress);
        String ipSubject = "GUEST_IP:" + normalizedIp;
        String deviceSubject = "GUEST_DEVICE:" + normalizedIp + ":" + guestDeviceId.trim();
        checkAllowed(ipSubject);
        checkAllowed(deviceSubject);
        checkDailyAllowed(ipSubject, guestDailyLimit);
        checkDailyAllowed(deviceSubject, guestDailyLimit);
    }

    private String normalizeIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "UNKNOWN";
        }
        return ipAddress.trim();
    }

    private void checkDailyAllowed(String subject, int limit) {
        LocalDate today = LocalDate.now(clock);
        rateLimiter.checkAllowed(
                RateLimitKeys.hashed("ai-route:daily:" + today, subject),
                limit,
                ttlUntilTomorrow(),
                DAILY_LIMIT_MESSAGE
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
