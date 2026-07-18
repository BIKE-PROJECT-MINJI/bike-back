package com.bikeprojectminji.bikeback.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.bikeprojectminji.bikeback.global.exception.RetryableServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.exception.RedisUnavailableException;
import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings({"rawtypes", "unchecked"})
class RedisFixedWindowRateLimiterTest {

    @Test
    @DisplayName("Redis fixed-window limiter는 한도를 초과하면 429 예외를 던진다")
    void checkAllowedRejectsWhenRedisCountExceedsLimit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate:key")), eq("60000")))
                .willReturn(3L);
        RedisFixedWindowRateLimiter rateLimiter = new RedisFixedWindowRateLimiter(redisTemplate);

        assertThatThrownBy(() -> rateLimiter.checkAllowed("rate:key", 2, Duration.ofMinutes(1), "too many"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("too many");
    }

    @Test
    @DisplayName("Redis fixed-window limiter는 저장소 장애를 retry 가능한 503으로 fail-closed 처리한다")
    void checkAllowedFailsClosedWhenRedisUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate:key")), eq("60000")))
                .willThrow(new IllegalStateException("redis down"));
        RedisFixedWindowRateLimiter rateLimiter = new RedisFixedWindowRateLimiter(redisTemplate);

        assertThatThrownBy(() -> rateLimiter.checkAllowed("rate:key", 2, Duration.ofMinutes(1), "too many"))
                .isInstanceOfSatisfying(RetryableServiceUnavailableException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getMessage())
                            .isEqualTo(RedisUnavailableException.MESSAGE);
                    org.assertj.core.api.Assertions.assertThat(exception.getErrorCode()).isEqualTo("REDIS_UNAVAILABLE");
                    org.assertj.core.api.Assertions.assertThat(exception.getRetryAfterSeconds()).isEqualTo(1);
                });
    }
}
