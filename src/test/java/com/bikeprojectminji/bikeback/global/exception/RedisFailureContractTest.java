package com.bikeprojectminji.bikeback.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lettuce.core.RedisCommandTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class RedisFailureContractTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RedisFailureProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("Redis 연결 장애는 stable errorCode와 Retry-After를 가진 503으로 응답한다")
    void redisConnectionFailureReturnsRetryableServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/redis-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.errorCode").value("REDIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(1));
    }

    @Test
    @DisplayName("Redis 명령 timeout도 연결 장애와 같은 retry 가능한 503 계약을 사용한다")
    void redisCommandTimeoutReturnsRetryableServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/redis-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.errorCode").value("REDIS_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(1));
    }

    @RestController
    private static class RedisFailureProbeController {

        @GetMapping("/test/redis-failure")
        String fail() {
            throw new RedisConnectionFailureException("synthetic redis outage");
        }

        @GetMapping("/test/redis-timeout")
        String timeout() {
            throw new QueryTimeoutException(
                    "synthetic redis command timeout",
                    new RedisCommandTimeoutException("synthetic timeout")
            );
        }
    }
}
