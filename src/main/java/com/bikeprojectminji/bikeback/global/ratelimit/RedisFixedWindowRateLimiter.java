package com.bikeprojectminji.bikeback.global.ratelimit;

import com.bikeprojectminji.bikeback.global.exception.ServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "bike.rate-limit.store", havingValue = "redis", matchIfMissing = true)
public class RedisFixedWindowRateLimiter implements FixedWindowRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowRateLimiter.class);
    private static final RedisScript<Long> INCREMENT_WITH_EXPIRE_SCRIPT = RedisScript.of("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);
    private static final String UNAVAILABLE_MESSAGE = "요청 제한 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    private final StringRedisTemplate stringRedisTemplate;

    public RedisFixedWindowRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public void checkAllowed(String key, int limit, Duration ttl, String limitMessage) {
        int effectiveLimit = Math.max(1, limit);
        Duration effectiveTtl = normalizeTtl(ttl);
        try {
            Long count = stringRedisTemplate.execute(
                    INCREMENT_WITH_EXPIRE_SCRIPT,
                    List.of(key),
                    String.valueOf(effectiveTtl.toMillis())
            );
            if (count == null) {
                throw new IllegalStateException("Redis rate limit script returned null.");
            }
            if (count > effectiveLimit) {
                throw new TooManyRequestsException(limitMessage);
            }
        } catch (TooManyRequestsException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("rate_limit_store_unavailable key={}", key, exception);
            throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE);
        }
    }

    private Duration normalizeTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return ttl;
    }
}
