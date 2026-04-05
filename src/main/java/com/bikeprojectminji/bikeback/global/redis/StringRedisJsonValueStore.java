package com.bikeprojectminji.bikeback.global.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class StringRedisJsonValueStore implements RedisJsonValueStore {

    // 도메인별 Redis key/TTL 의미는 각 도메인이 소유하고,
    // 이 클래스는 JSON 직렬화/역직렬화와 저수준 get/set만 담당한다.

    private static final Logger log = LoggerFactory.getLogger(StringRedisJsonValueStore.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public StringRedisJsonValueStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        String payload = stringRedisTemplate.opsForValue().get(key);
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, type));
        } catch (JsonProcessingException exception) {
            log.warn("failed to deserialize redis payload key={} type={}", key, type.getSimpleName(), exception);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Redis payload 직렬화에 실패했습니다.", exception);
        }
    }
}
