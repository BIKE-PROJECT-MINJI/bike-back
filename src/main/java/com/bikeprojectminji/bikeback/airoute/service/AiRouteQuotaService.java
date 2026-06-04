package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRouteQuotaService {

    private static final String DEFAULT_LIMIT_MESSAGE = "AI 경로 추천 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";
    private static final long WINDOW_SECONDS = 60L;

    private final Clock clock;
    private final int perMinuteLimit;
    private final Map<String, QuotaWindow> windows = new ConcurrentHashMap<>();

    public AiRouteQuotaService(
            Clock clock,
            @Value("${ai-route.quota.per-minute:20}") int perMinuteLimit
    ) {
        this.clock = clock;
        this.perMinuteLimit = Math.max(1, perMinuteLimit);
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

    private record QuotaWindow(Instant startedAt, int count) {

        private boolean isExpired(Instant now) {
            return !startedAt.plusSeconds(WINDOW_SECONDS).isAfter(now);
        }

        private QuotaWindow incremented() {
            return new QuotaWindow(startedAt, count + 1);
        }
    }
}
