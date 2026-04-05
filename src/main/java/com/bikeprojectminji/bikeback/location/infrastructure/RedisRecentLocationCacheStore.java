package com.bikeprojectminji.bikeback.location.infrastructure;

import com.bikeprojectminji.bikeback.global.redis.RedisJsonValueStore;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheStore;
import com.bikeprojectminji.bikeback.location.service.RecentLocationSnapshot;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RedisRecentLocationCacheStore implements RecentLocationCacheStore {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisJsonValueStore redisJsonValueStore;

    public RedisRecentLocationCacheStore(RedisJsonValueStore redisJsonValueStore) {
        this.redisJsonValueStore = redisJsonValueStore;
    }

    @Override
    public Optional<RecentLocationSnapshot> find(String subject) {
        return redisJsonValueStore.get(key(subject), RecentLocationSnapshot.class);
    }

    @Override
    public void save(String subject, RecentLocationSnapshot snapshot) {
        redisJsonValueStore.set(key(subject), snapshot, TTL);
    }

    private String key(String subject) {
        return "location:recent:" + subject;
    }
}
