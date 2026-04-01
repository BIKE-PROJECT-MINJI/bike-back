package com.bikeprojectminji.bikeback.infrastructure.weather;

import com.bikeprojectminji.bikeback.service.weather.LastSuccessWeatherStore;
import com.bikeprojectminji.bikeback.service.weather.WeatherLocationKey;
import com.bikeprojectminji.bikeback.service.weather.WeatherSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisLastSuccessWeatherStore implements LastSuccessWeatherStore {

    private static final Logger log = LoggerFactory.getLogger(RedisLastSuccessWeatherStore.class);
    private static final Duration TTL = Duration.ofMinutes(61);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisLastSuccessWeatherStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<WeatherSnapshot> find(WeatherLocationKey locationKey) {
        String payload = stringRedisTemplate.opsForValue().get(key(locationKey));
        if (payload == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(payload, WeatherSnapshot.class));
        } catch (JsonProcessingException exception) {
            log.warn("failed to deserialize weather snapshot key={}", key(locationKey), exception);
            return Optional.empty();
        }
    }

    @Override
    public void save(WeatherLocationKey locationKey, WeatherSnapshot snapshot) {
        try {
            stringRedisTemplate.opsForValue().set(key(locationKey), objectMapper.writeValueAsString(snapshot), TTL);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("날씨 스냅샷 직렬화에 실패했습니다.", exception);
        }
    }

    private String key(WeatherLocationKey locationKey) {
        return "weather:last-success:" + locationKey.lat().toPlainString() + ":" + locationKey.lon().toPlainString();
    }
}
