package com.bikeprojectminji.bikeback.weather.infrastructure;

import com.bikeprojectminji.bikeback.global.redis.RedisJsonValueStore;
import com.bikeprojectminji.bikeback.weather.service.LastSuccessWeatherStore;
import com.bikeprojectminji.bikeback.weather.service.WeatherLocationKey;
import com.bikeprojectminji.bikeback.weather.service.WeatherSnapshot;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RedisLastSuccessWeatherStore implements LastSuccessWeatherStore {

    // weather 마지막 성공값은 60분 fallback 정책을 지원하기 위해 61분 TTL로 저장한다.
    private static final Duration TTL = Duration.ofMinutes(61);

    private final RedisJsonValueStore redisJsonValueStore;

    public RedisLastSuccessWeatherStore(RedisJsonValueStore redisJsonValueStore) {
        this.redisJsonValueStore = redisJsonValueStore;
    }

    @Override
    public Optional<WeatherSnapshot> find(WeatherLocationKey locationKey) {
        return redisJsonValueStore.get(key(locationKey), WeatherSnapshot.class);
    }

    @Override
    public void save(WeatherLocationKey locationKey, WeatherSnapshot snapshot) {
        redisJsonValueStore.set(key(locationKey), snapshot, TTL);
    }

    private String key(WeatherLocationKey locationKey) {
        return "weather:last-success:" + locationKey.lat().toPlainString() + ":" + locationKey.lon().toPlainString();
    }
}
