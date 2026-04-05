package com.bikeprojectminji.bikeback.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecentLocationCacheServiceTest {

    @Mock
    private RecentLocationCacheStore recentLocationCacheStore;

    private RecentLocationCacheService recentLocationCacheService;

    @BeforeEach
    void setUp() {
        recentLocationCacheService = new RecentLocationCacheService(recentLocationCacheStore);
    }

    @Test
    @DisplayName("최근 위치 캐시 조회는 저장소 결과를 그대로 반환한다")
    void findReturnsCachedSnapshot() {
        RecentLocationSnapshot snapshot = new RecentLocationSnapshot(
                10L,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                12,
                RecentLocationStatus.COMPLETE,
                OffsetDateTime.parse("2026-04-05T10:00:00+09:00")
        );
        given(recentLocationCacheStore.find("subject-1")).willReturn(Optional.of(snapshot));

        Optional<RecentLocationSnapshot> result = recentLocationCacheService.find("subject-1");

        assertThat(result).contains(snapshot);
    }

    @Test
    @DisplayName("주행 저장 완료 시 마지막 위치를 COMPLETE 상태로 캐시에 저장한다")
    void saveCompletedStoresCompleteSnapshot() {
        OffsetDateTime capturedAt = OffsetDateTime.parse("2026-04-05T10:00:00+09:00");

        recentLocationCacheService.saveCompleted(
                "subject-1",
                44L,
                22,
                BigDecimal.valueOf(37.1),
                BigDecimal.valueOf(127.2),
                capturedAt
        );

        verify(recentLocationCacheStore).save("subject-1", new RecentLocationSnapshot(
                44L,
                BigDecimal.valueOf(37.1),
                BigDecimal.valueOf(127.2),
                22,
                RecentLocationStatus.COMPLETE,
                capturedAt
        ));
    }
}
