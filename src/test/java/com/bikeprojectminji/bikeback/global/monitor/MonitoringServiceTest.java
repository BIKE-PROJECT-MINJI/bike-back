package com.bikeprojectminji.bikeback.global.monitor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class MonitoringServiceTest {

    @Test
    @DisplayName("Redis ping timeout은 예외를 전파하지 않고 readiness를 degraded로 만든다")
    void redisCommandTimeoutDegradesReadiness() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        given(jdbcTemplate.queryForObject("select 1", Integer.class)).willReturn(1);
        given(redisTemplate.execute(any(RedisCallback.class))).willThrow(new QueryTimeoutException(
                "synthetic redis command timeout",
                new RedisCommandTimeoutException("synthetic timeout")
        ));

        MonitoringStatusResponse status = new MonitoringService(jdbcTemplate, redisTemplate).getStatus();

        assertThat(status.status()).isEqualTo("degraded");
        assertThat(status.database().status()).isEqualTo("ok");
        assertThat(status.redis().status()).isEqualTo("fail");
    }
}
