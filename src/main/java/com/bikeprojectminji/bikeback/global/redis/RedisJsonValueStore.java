package com.bikeprojectminji.bikeback.global.redis;

import java.time.Duration;
import java.util.Optional;

public interface RedisJsonValueStore {

    <T> Optional<T> get(String key, Class<T> type);

    void set(String key, Object value, Duration ttl);
}
