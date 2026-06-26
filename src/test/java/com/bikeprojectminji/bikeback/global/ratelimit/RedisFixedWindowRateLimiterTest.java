package com.bikeprojectminji.bikeback.global.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.bikeprojectminji.bikeback.global.exception.ServiceUnavailableException;
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
    @DisplayName("Redis fixed-window limiter는 저장소 장애를 503 예외로 격리한다")
    void checkAllowedFailsClosedWhenRedisUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate:key")), eq("60000")))
                .willThrow(new IllegalStateException("redis down"));
        RedisFixedWindowRateLimiter rateLimiter = new RedisFixedWindowRateLimiter(redisTemplate);

        assertThatThrownBy(() -> rateLimiter.checkAllowed("rate:key", 2, Duration.ofMinutes(1), "too many"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("요청 제한 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
