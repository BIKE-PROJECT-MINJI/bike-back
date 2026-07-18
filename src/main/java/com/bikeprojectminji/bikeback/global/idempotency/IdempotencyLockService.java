package com.bikeprojectminji.bikeback.global.idempotency;

import com.bikeprojectminji.bikeback.global.exception.ServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyLockService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyLockService.class);
    private static final String KEY_PREFIX = "bike:idempotency-lock:";
    private static final Duration DEFAULT_LOCK_TTL = Duration.ofSeconds(10);
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_MIN_WAIT_INTERVAL = Duration.ofMillis(50);
    private static final Duration DEFAULT_MAX_WAIT_INTERVAL = Duration.ofMillis(100);
    private static final String BACKPRESSURE_MESSAGE = "같은 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.";
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final Duration lockTtl;
    private final Duration waitTimeout;
    private final Duration minWaitInterval;
    private final Duration maxWaitInterval;
    private final Supplier<String> lockTokenSupplier;

    @Autowired
    public IdempotencyLockService(StringRedisTemplate stringRedisTemplate, BikeMetricsRecorder bikeMetricsRecorder) {
        this(
                stringRedisTemplate,
                bikeMetricsRecorder,
                DEFAULT_LOCK_TTL,
                DEFAULT_WAIT_TIMEOUT,
                DEFAULT_MIN_WAIT_INTERVAL,
                DEFAULT_MAX_WAIT_INTERVAL,
                () -> UUID.randomUUID().toString()
        );
    }

    IdempotencyLockService(
            StringRedisTemplate stringRedisTemplate,
            BikeMetricsRecorder bikeMetricsRecorder,
            Duration lockTtl,
            Duration waitTimeout,
            Duration minWaitInterval,
            Duration maxWaitInterval,
            Supplier<String> lockTokenSupplier
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
        this.lockTtl = normalizePositive(lockTtl, DEFAULT_LOCK_TTL);
        this.waitTimeout = normalizeNonNegative(waitTimeout, DEFAULT_WAIT_TIMEOUT);
        this.minWaitInterval = normalizePositive(minWaitInterval, DEFAULT_MIN_WAIT_INTERVAL);
        this.maxWaitInterval = normalizePositive(maxWaitInterval, DEFAULT_MAX_WAIT_INTERVAL);
        this.lockTokenSupplier = lockTokenSupplier;
    }

    public <T> T executeOrWait(
            String operation,
            String key,
            Supplier<Optional<T>> existingLookup,
            Supplier<T> creator
    ) {
        return executeOrWait(operation, key, waitTimeout, existingLookup, creator);
    }

    public <T> T executeOrWait(
            String operation,
            String key,
            Duration waitTimeout,
            Supplier<Optional<T>> existingLookup,
            Supplier<T> creator
    ) {
        return executeOrWait(operation, key, waitTimeout, minWaitInterval, maxWaitInterval, existingLookup, creator);
    }

    public <T> T executeOrWait(
            String operation,
            String key,
            Duration waitTimeout,
            Duration minWaitInterval,
            Duration maxWaitInterval,
            Supplier<Optional<T>> existingLookup,
            Supplier<T> creator
    ) {
        return executeOrWaitInternal(operation, key, waitTimeout, minWaitInterval, maxWaitInterval, true, existingLookup, creator);
    }

    public <T> T executeOrWaitAfterContention(
            String operation,
            String key,
            Supplier<Optional<T>> existingLookup,
            Supplier<T> creator
    ) {
        return executeOrWaitInternal(operation, key, waitTimeout, minWaitInterval, maxWaitInterval, false, existingLookup, creator);
    }

    public <T> T executeOrWaitAfterContention(
            String operation,
            String key,
            Duration waitTimeout,
            Duration minWaitInterval,
            Duration maxWaitInterval,
            Supplier<Optional<T>> existingLookup,
            Supplier<T> creator
    ) {
        return executeOrWaitInternal(operation, key, waitTimeout, minWaitInterval, maxWaitInterval, false, existingLookup, creator);
    }

    private <T> T executeOrWaitInternal(
            String operation,
            String key,
            Duration waitTimeout,
            Duration minWaitInterval,
            Duration maxWaitInterval,
            boolean lookupBeforeLock,
            Supplier<Optional<T>> existingLookup,
            Supplier<T> creator
    ) {
        if (lookupBeforeLock) {
            Optional<T> existing = existingLookup.get();
            if (existing.isPresent()) {
                record(operation, "existing_before_lock");
                return existing.get();
            }
        }
        if (key == null || key.isBlank()) {
            record(operation, "missing_key");
            return creator.get();
        }

        String redisKey = KEY_PREFIX + key;
        String token = lockTokenSupplier.get();
        if (!tryAcquire(operation, redisKey, token)) {
            return waitForExisting(
                    operation,
                    normalizeNonNegative(waitTimeout, this.waitTimeout),
                    normalizePositive(minWaitInterval, this.minWaitInterval),
                    normalizePositive(maxWaitInterval, this.maxWaitInterval),
                    existingLookup
            );
        }

        try {
            return creator.get();
        } finally {
            release(operation, redisKey, token);
        }
    }

    private boolean tryAcquire(String operation, String redisKey, String token) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, token, lockTtl);
            if (Boolean.TRUE.equals(acquired)) {
                record(operation, "acquired");
                return true;
            }
            record(operation, "contended");
            return false;
        } catch (RuntimeException exception) {
            log.warn("idempotency_lock_redis_unavailable operation={}", operation, exception);
            record(operation, "redis_unavailable");
            return true;
        }
    }

    private <T> T waitForExisting(
            String operation,
            Duration waitTimeout,
            Duration minWaitInterval,
            Duration maxWaitInterval,
            Supplier<Optional<T>> existingLookup
    ) {
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + waitTimeout.toNanos();
        while (System.nanoTime() <= deadlineNanos) {
            sleepBeforeRetry(operation, minWaitInterval, maxWaitInterval);
            Optional<T> existing = existingLookup.get();
            if (existing.isPresent()) {
                Duration waited = Duration.ofNanos(System.nanoTime() - startedNanos);
                bikeMetricsRecorder.recordIdempotencyLockWaitDuration(operation, "existing_found", waited);
                record(operation, "existing_after_wait");
                return existing.get();
            }
        }
        Duration waited = Duration.ofNanos(System.nanoTime() - startedNanos);
        bikeMetricsRecorder.recordIdempotencyLockWaitDuration(operation, "timeout", waited);
        record(operation, "wait_timeout");
        throw new ServiceUnavailableException(BACKPRESSURE_MESSAGE);
    }

    private void sleepBeforeRetry(String operation, Duration minWaitInterval, Duration maxWaitInterval) {
        long minMillis = Math.max(1, minWaitInterval.toMillis());
        long maxMillis = Math.max(minMillis, maxWaitInterval.toMillis());
        long sleepMillis = minMillis == maxMillis
                ? minMillis
                : ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            record(operation, "interrupted");
            throw new ServiceUnavailableException(BACKPRESSURE_MESSAGE);
        }
    }

    private void release(String operation, String redisKey, String token) {
        try {
            stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(redisKey), token);
            record(operation, "released");
        } catch (RuntimeException exception) {
            log.warn("idempotency_lock_release_failed operation={}", operation, exception);
            record(operation, "release_failed");
        }
    }

    private void record(String operation, String outcome) {
        bikeMetricsRecorder.recordIdempotencyLock(operation, outcome);
    }

    private Duration normalizePositive(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    private Duration normalizeNonNegative(Duration value, Duration fallback) {
        if (value == null || value.isNegative()) {
            return fallback;
        }
        return value;
    }
}
