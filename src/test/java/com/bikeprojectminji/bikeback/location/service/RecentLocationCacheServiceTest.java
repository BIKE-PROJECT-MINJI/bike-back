package com.bikeprojectminji.bikeback.location.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
class RecentLocationCacheServiceTest {

    @Mock
    private RecentLocationCacheStore recentLocationCacheStore;

    @Mock
    private AuthService authService;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    private RecentLocationCacheService recentLocationCacheService;

    @BeforeEach
    void setUp() {
        recentLocationCacheService = new RecentLocationCacheService(
                recentLocationCacheStore,
                authService,
                rideRecordRepository,
                rideRecordPointRepository
        );
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
        verifyNoInteractions(authService, rideRecordRepository, rideRecordPointRepository);
    }

    @Test
    @DisplayName("최근 위치 캐시가 없으면 DB의 최근 READY 주행 마지막 포인트로 fallback한다")
    void findFallsBackToLatestReadyRideRecordLastPoint() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded", "bikeoasis", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(
                1L,
                OffsetDateTime.parse("2026-05-26T09:00:00+09:00"),
                OffsetDateTime.parse("2026-05-26T10:00:00+09:00"),
                12000,
                3600
        );
        org.springframework.test.util.ReflectionTestUtils.setField(rideRecord, "id", 44L);
        rideRecord.markReady(OffsetDateTime.parse("2026-05-26T10:01:00+09:00"));
        RideRecordPointEntity lastPoint = new RideRecordPointEntity(
                44L,
                22,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                OffsetDateTime.parse("2026-05-26T10:00:00+09:00"),
                null,
                null,
                null,
                null,
                null,
                null
        );
        given(recentLocationCacheStore.find("1")).willReturn(Optional.empty());
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findFirstByOwnerUserIdAndFinalizationStatusOrderByEndedAtDescIdDesc(1L, RideRecordFinalizationStatus.READY.name()))
                .willReturn(Optional.of(rideRecord));
        given(rideRecordPointRepository.findTopByRideRecordIdOrderByPointOrderDesc(44L)).willReturn(Optional.of(lastPoint));

        Optional<RecentLocationSnapshot> result = recentLocationCacheService.find("1");

        assertThat(result).contains(new RecentLocationSnapshot(
                44L,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                22,
                RecentLocationStatus.COMPLETE,
                OffsetDateTime.parse("2026-05-26T10:00:00+09:00")
        ));
        verify(recentLocationCacheStore).save("1", result.get());
    }

    @Test
    @DisplayName("최근 위치 캐시와 DB READY 주행이 모두 없으면 빈 결과를 반환한다")
    void findReturnsEmptyWhenCacheAndDbFallbackAreMissing() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded", "bikeoasis", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        given(recentLocationCacheStore.find("1")).willReturn(Optional.empty());
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findFirstByOwnerUserIdAndFinalizationStatusOrderByEndedAtDescIdDesc(1L, RideRecordFinalizationStatus.READY.name()))
                .willReturn(Optional.empty());

        Optional<RecentLocationSnapshot> result = recentLocationCacheService.find("1");

        assertThat(result).isEmpty();
        verifyNoInteractions(rideRecordPointRepository);
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

    @Test
    @DisplayName("최근 위치 로그는 원본 좌표를 출력하지 않는다")
    void saveCompletedDoesNotLogRawCoordinates(CapturedOutput output) {
        recentLocationCacheService.saveCompleted(
                "subject-1",
                44L,
                22,
                BigDecimal.valueOf(37.1234567),
                BigDecimal.valueOf(127.7654321),
                OffsetDateTime.parse("2026-04-05T10:00:00+09:00")
        );

        assertThat(output).doesNotContain("37.1234567");
        assertThat(output).doesNotContain("127.7654321");
    }
}
