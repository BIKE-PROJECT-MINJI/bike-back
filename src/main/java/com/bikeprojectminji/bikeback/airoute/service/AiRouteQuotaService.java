package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRouteQuotaService {

    private static final String DEFAULT_LIMIT_MESSAGE = "AI 경로 추천 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
    private static final String DAILY_LIMIT_MESSAGE = "오늘 사용할 수 있는 AI 코스 생성 횟수를 모두 사용했습니다.";
    private static final long WINDOW_SECONDS = 60L;

    private final Clock clock;
    private final int perMinuteLimit;
    private final int authenticatedDailyLimit;
    private final int guestDailyLimit;
    private final Map<String, QuotaWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, DailyQuotaWindow> dailyWindows = new ConcurrentHashMap<>();

    public AiRouteQuotaService(
            Clock clock,
            @Value("${ai-route.quota.per-minute:20}") int perMinuteLimit,
            @Value("${ai-route.quota.authenticated-daily:20}") int authenticatedDailyLimit,
            @Value("${ai-route.quota.guest-daily:3}") int guestDailyLimit
    ) {
        this.clock = clock;
        this.perMinuteLimit = Math.max(1, perMinuteLimit);
        this.authenticatedDailyLimit = Math.max(1, authenticatedDailyLimit);
        this.guestDailyLimit = Math.max(1, guestDailyLimit);
    }

    public void checkAllowed(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new TooManyRequestsException(DEFAULT_LIMIT_MESSAGE);
        }
        Instant now = clock.instant();
        QuotaWindow window = windows.compute(subject, (ignored, current) -> {
            if (current == null || current.isExpired(now)) {
                return new QuotaWindow(now, 1);
            }
            return current.incremented();
        });
        if (window.count() > perMinuteLimit) {
            throw new TooManyRequestsException(DEFAULT_LIMIT_MESSAGE);
        }
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
        DailyQuotaWindow window = dailyWindows.compute(subject, (ignored, current) -> {
            if (current == null || !current.day().equals(today)) {
                return new DailyQuotaWindow(today, 1);
            }
            return current.incremented();
        });
        if (window.count() > limit) {
            throw new TooManyRequestsException(DAILY_LIMIT_MESSAGE);
        }
    }

    private record QuotaWindow(Instant startedAt, int count) {

        private boolean isExpired(Instant now) {
            return !startedAt.plusSeconds(WINDOW_SECONDS).isAfter(now);
        }

        private QuotaWindow incremented() {
            return new QuotaWindow(startedAt, count + 1);
        }
    }

    private record DailyQuotaWindow(LocalDate day, int count) {

        private DailyQuotaWindow incremented() {
            return new DailyQuotaWindow(day, count + 1);
        }
    }
}
