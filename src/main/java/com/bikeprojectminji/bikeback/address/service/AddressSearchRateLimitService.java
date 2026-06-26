package com.bikeprojectminji.bikeback.address.service;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AddressSearchRateLimitService {

    private static final String LIMIT_MESSAGE = "주소 검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
    private static final long WINDOW_SECONDS = 60L;

    private final Clock clock;
    private final int perMinuteLimit;
    private final int dailyLimit;
    private final Map<String, QuotaWindow> windows = new ConcurrentHashMap<>();
    private final Map<String, DailyQuotaWindow> dailyWindows = new ConcurrentHashMap<>();

    public AddressSearchRateLimitService(
            Clock clock,
            @Value("${address.search.quota.per-minute:30}") int perMinuteLimit,
            @Value("${address.search.quota.per-day:300}") int dailyLimit
    ) {
        this.clock = clock;
        this.perMinuteLimit = Math.max(1, perMinuteLimit);
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    public void checkAllowed(String ipAddress) {
        String subject = "ADDRESS_IP:" + normalizeIp(ipAddress);
        Instant now = clock.instant();
        QuotaWindow window = windows.compute(subject, (ignored, current) -> {
            if (current == null || current.isExpired(now)) {
                return new QuotaWindow(now, 1);
            }
            return current.incremented();
        });
        if (window.count() > perMinuteLimit) {
            throw new TooManyRequestsException(LIMIT_MESSAGE);
        }

        LocalDate today = LocalDate.now(clock);
        DailyQuotaWindow dailyWindow = dailyWindows.compute(subject, (ignored, current) -> {
            if (current == null || !current.day().equals(today)) {
                return new DailyQuotaWindow(today, 1);
            }
            return current.incremented();
        });
        if (dailyWindow.count() > dailyLimit) {
            throw new TooManyRequestsException(LIMIT_MESSAGE);
        }
    }

    private String normalizeIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "UNKNOWN";
        }
        return ipAddress.trim();
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
