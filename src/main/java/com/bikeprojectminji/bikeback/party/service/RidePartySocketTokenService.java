package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.global.redis.RedisJsonValueStore;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RidePartySocketTokenService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(5);
    private static final String KEY_PREFIX = "bike:party-socket-token:";

    private final RedisJsonValueStore redisJsonValueStore;
    private final Clock clock;

    public RidePartySocketTokenService(RedisJsonValueStore redisJsonValueStore, Clock clock) {
        this.redisJsonValueStore = redisJsonValueStore;
        this.clock = clock;
    }

    public IssuedRidePartySocketToken issue(Long partyId, Long userId) {
        String token = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(TOKEN_TTL);
        redisJsonValueStore.set(key(token), new RidePartySocketTokenPayload(partyId, userId, expiresAt), TOKEN_TTL);
        return new IssuedRidePartySocketToken(token, expiresAt);
    }

    public Optional<RidePartySocketTokenPayload> consume(String token, Long expectedPartyId) {
        if (token == null || token.isBlank() || expectedPartyId == null) {
            return Optional.empty();
        }
        String key = key(token.trim());
        Optional<RidePartySocketTokenPayload> payload = redisJsonValueStore.get(key, RidePartySocketTokenPayload.class);
        redisJsonValueStore.delete(key);
        return payload
                .filter(value -> expectedPartyId.equals(value.partyId()))
                .filter(value -> value.expiresAt() == null || value.expiresAt().isAfter(OffsetDateTime.now(clock)));
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }

    public record IssuedRidePartySocketToken(String token, OffsetDateTime expiresAt) {
    }
}
