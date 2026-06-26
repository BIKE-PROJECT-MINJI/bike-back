package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.party.entity.RidePartyLocationPointEntity;
import com.bikeprojectminji.bikeback.party.repository.RidePartyLocationPointRepository;
import com.bikeprojectminji.bikeback.party.websocket.RidePartyLocationMessage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RidePartyLocationServiceTest {

    private final RidePartyLocationPointRepository locationPointRepository = org.mockito.Mockito.mock(RidePartyLocationPointRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);
    private final RidePartyLocationService service = new RidePartyLocationService(locationPointRepository, clock);

    @Test
    @DisplayName("파티 위치 메시지는 원본 point로 저장된다")
    void saveLocationStoresPartyLocationPoint() {
        RidePartyLocationMessage location = new RidePartyLocationMessage(
                BigDecimal.valueOf(37.5001),
                BigDecimal.valueOf(127.0002),
                BigDecimal.valueOf(8.5),
                BigDecimal.valueOf(3.2),
                BigDecimal.valueOf(180),
                OffsetDateTime.parse("2026-06-26T00:00:01Z")
        );
        ArgumentCaptor<RidePartyLocationPointEntity> captor = ArgumentCaptor.forClass(RidePartyLocationPointEntity.class);

        service.saveLocation(20L, 2L, location);

        verify(locationPointRepository).save(captor.capture());
        RidePartyLocationPointEntity saved = captor.getValue();
        assertThat(saved.getPartyId()).isEqualTo(20L);
        assertThat(saved.getUserId()).isEqualTo(2L);
        assertThat(saved.getLatitude()).isEqualByComparingTo("37.5001");
        assertThat(saved.getLongitude()).isEqualByComparingTo("127.0002");
        assertThat(saved.getCapturedAt()).isEqualTo(OffsetDateTime.parse("2026-06-26T00:00:01Z"));
    }

    @Test
    @DisplayName("파티 위치 cleanup은 생성 후 24시간이 지난 point를 삭제한다")
    void deleteExpiredLocationPointsDeletesOlderThanTwentyFourHours() {
        given(locationPointRepository.deleteByCreatedAtBefore(any())).willReturn(3);

        int deletedCount = service.deleteExpiredLocationPoints();

        assertThat(deletedCount).isEqualTo(3);
        verify(locationPointRepository).deleteByCreatedAtBefore(OffsetDateTime.parse("2026-06-25T00:00:00Z"));
    }
}
