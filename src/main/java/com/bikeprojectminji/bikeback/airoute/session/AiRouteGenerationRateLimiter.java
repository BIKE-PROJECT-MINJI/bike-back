package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AiRouteGenerationRateLimiter {

    private static final String MESSAGE = "AI 코스 생성 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.";

    private final Map<String, WindowCounter> minuteCounters = new ConcurrentHashMap<>();
    private final Map<String, DailyCounter> dailyCounters = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int perMinuteLimit;
    private final int dailyLimit;

    public AiRouteGenerationRateLimiter(
            Clock clock,
            @Value("${ai-route.generation.quota.per-minute:3}") int perMinuteLimit,
            @Value("${ai-route.generation.quota.per-day:30}") int dailyLimit
    ) {
        this.clock = clock;
        this.perMinuteLimit = perMinuteLimit;
        this.dailyLimit = dailyLimit;
    }

    public void checkAllowed(String userKey) {
        String key = userKey == null ? "anonymous" : userKey;
        long minuteBucket = ChronoUnit.MINUTES.between(java.time.Instant.EPOCH, clock.instant());
        LocalDate today = LocalDate.now(clock);

        int minuteCount = minuteCounters.compute(key, (ignored, current) -> nextMinuteCounter(current, minuteBucket)).count();
        if (minuteCount > perMinuteLimit) {
            throw new TooManyRequestsException(MESSAGE);
        }

        int dailyCount = dailyCounters.compute(key, (ignored, current) -> nextDailyCounter(current, today)).count();
        if (dailyCount > dailyLimit) {
            throw new TooManyRequestsException(MESSAGE);
        }
    }

    private WindowCounter nextMinuteCounter(WindowCounter current, long minuteBucket) {
        if (current == null || current.bucket() != minuteBucket) {
            return new WindowCounter(minuteBucket, 1);
        }
        return new WindowCounter(minuteBucket, current.count() + 1);
    }

    private DailyCounter nextDailyCounter(DailyCounter current, LocalDate today) {
        if (current == null || !current.date().equals(today)) {
            return new DailyCounter(today, 1);
        }
        return new DailyCounter(today, current.count() + 1);
    }

    private record WindowCounter(long bucket, int count) {
    }

    private record DailyCounter(LocalDate date, int count) {
    }
}
