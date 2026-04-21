package com.bikeprojectminji.bikeback.global.monitor;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public MonitoringService(JdbcTemplate jdbcTemplate, StringRedisTemplate stringRedisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public MonitoringStatusResponse getStatus() {
        DependencyStatusResponse database = checkDatabase();
        DependencyStatusResponse redis = checkRedis();
        return new MonitoringStatusResponse(
                "bike-back",
                isHealthy(database, redis) ? "ok" : "degraded",
                OffsetDateTime.now(),
                database,
                redis
        );
    }

    private boolean isHealthy(DependencyStatusResponse database, DependencyStatusResponse redis) {
        return "ok".equals(database.status()) && "ok".equals(redis.status());
    }

    private DependencyStatusResponse checkDatabase() {
        try {
            Integer value = jdbcTemplate.queryForObject("select 1", Integer.class);
            return new DependencyStatusResponse("ok", value != null && value == 1 ? "select 1 success" : "unexpected database response");
        } catch (DataAccessException exception) {
            log.warn("database monitoring check failed", exception);
            return new DependencyStatusResponse("fail", summarize(exception));
        }
    }

    private DependencyStatusResponse checkRedis() {
        try {
            String pong = stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if (pong == null || pong.isBlank()) {
                return new DependencyStatusResponse("fail", "empty redis ping response");
            }
            return new DependencyStatusResponse("ok", pong);
        } catch (RedisConnectionFailureException exception) {
            log.warn("redis monitoring check failed", exception);
            return new DependencyStatusResponse("fail", summarize(exception));
        }
    }

    private String summarize(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }
}
