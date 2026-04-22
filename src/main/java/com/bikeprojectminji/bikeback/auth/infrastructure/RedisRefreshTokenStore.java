package com.bikeprojectminji.bikeback.auth.infrastructure;

import com.bikeprojectminji.bikeback.auth.service.RefreshTokenSession;
import com.bikeprojectminji.bikeback.auth.service.RefreshTokenStore;
import com.bikeprojectminji.bikeback.global.redis.RedisJsonValueStore;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private final RedisJsonValueStore redisJsonValueStore;

    public RedisRefreshTokenStore(RedisJsonValueStore redisJsonValueStore) {
        this.redisJsonValueStore = redisJsonValueStore;
    }

    @Override
    public Optional<RefreshTokenSession> findBySubject(String subject) {
        return redisJsonValueStore.get(key(subject), RefreshTokenSession.class);
    }

    @Override
    public void save(String subject, RefreshTokenSession session, Duration ttl) {
        redisJsonValueStore.set(key(subject), session, ttl);
    }

    private String key(String subject) {
        return "auth:refresh-token:" + subject;
    }
}
