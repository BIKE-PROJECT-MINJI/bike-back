package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bikeprojectminji.bikeback.global.redis.RedisJsonValueStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RidePartySocketTokenServiceTest {

    private final RedisJsonValueStore redisJsonValueStore = org.mockito.Mockito.mock(RedisJsonValueStore.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);
    private final RidePartySocketTokenService service = new RidePartySocketTokenService(redisJsonValueStore, clock);

    @Test
    @DisplayName("파티 socket token은 Redis에 5분 TTL로 저장된다")
    void issueStoresTokenWithFiveMinuteTtl() {
        ArgumentCaptor<RidePartySocketTokenPayload> payloadCaptor = ArgumentCaptor.forClass(RidePartySocketTokenPayload.class);

        RidePartySocketTokenService.IssuedRidePartySocketToken issued = service.issue(20L, 2L);

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(OffsetDateTime.now(clock).plusMinutes(5));
        verify(redisJsonValueStore).set(
                eq("bike:party-socket-token:" + issued.token()),
                payloadCaptor.capture(),
                eq(Duration.ofMinutes(5))
        );
        assertThat(payloadCaptor.getValue().partyId()).isEqualTo(20L);
        assertThat(payloadCaptor.getValue().userId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("파티 socket token은 한 번 소비하면 삭제된다")
    void consumeDeletesTokenAfterLookup() {
        RidePartySocketTokenPayload payload = new RidePartySocketTokenPayload(
                20L,
                2L,
                OffsetDateTime.now(clock).plusMinutes(1)
        );
        when(redisJsonValueStore.get(any(), eq(RidePartySocketTokenPayload.class))).thenReturn(Optional.of(payload));

        Optional<RidePartySocketTokenPayload> result = service.consume("token-1", 20L);

        assertThat(result).contains(payload);
        verify(redisJsonValueStore).delete("bike:party-socket-token:token-1");
    }
}
