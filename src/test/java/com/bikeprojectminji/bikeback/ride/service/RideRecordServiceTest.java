package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.idempotency.IdempotencyLockService;
import com.bikeprojectminji.bikeback.global.exception.RetryableServiceUnavailableException;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordTraceRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;

@ExtendWith(MockitoExtension.class)
class RideRecordServiceTest {

    @Mock
    private AuthService authService;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private RideRecordRepository rideRecordRepository;

    @Mock
    private RideRecordPointRepository rideRecordPointRepository;

    @Mock
    private RecentLocationCacheService recentLocationCacheService;

    @Mock
    private RideRecordFinalizationService rideRecordFinalizationService;

    @Mock
    private TransactionOperations transactionOperations;

    @Mock
    private IdempotencyLockService idempotencyLockService;

    @Mock
    private RideSaveConcurrencyGate rideSaveConcurrencyGate;

    @InjectMocks
    private RideRecordService rideRecordService;

    @BeforeEach
    void executeTransactionsInline() {
        lenient().when(transactionOperations.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        lenient().when(idempotencyLockService.executeOrWait(any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<Optional<?>> existingLookup = invocation.getArgument(2);
            Optional<?> existing = existingLookup.get();
            if (existing.isPresent()) {
                return existing.get();
            }
            Supplier<?> creator = invocation.getArgument(3);
            return creator.get();
        });
        lenient().when(idempotencyLockService.executeOrWaitAfterContention(any(), any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> creator = invocation.getArgument(3);
            return creator.get();
        });
        lenient().doAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(rideSaveConcurrencyGate).execute(any());
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 10초 미만 duration을 거절한다")
    void saveRideRecordRejectsDurationUnderTenSeconds() {
        CreateRideRecordRequest request = new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T10:00:09+09:00"),
                new RideRecordSummaryRequest(42, 9),
                List.of(new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)))
        );

        assertThatThrownBy(() -> rideRecordService.saveRideRecord("1", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("주행 시작 후 10초 미만 기록은 저장되지 않습니다.");

        verifyNoInteractions(authService, rideRecordRepository, rideRecordPointRepository,
                recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 10초 duration이면 기존 finalization 흐름을 유지한다")
    void saveRideRecordAcceptsTenSecondDuration() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(1L, OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), OffsetDateTime.parse("2026-03-29T10:00:10+09:00"), 120, 10);
        ReflectionTestUtils.setField(savedRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T10:00:10+09:00"),
                new RideRecordSummaryRequest(120, 10),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        ));

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
        verify(rideRecordFinalizationService).requestFinalization(1001L);
    }

    @Test
    @DisplayName("웹 HUD 요약 저장은 route point 없이 기록만 저장하고 후처리를 요청하지 않는다")
    void saveRideRecordSummaryDoesNotPersistPointsOrRequestFinalization() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(
                1L,
                "web-hud-ride-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                18250,
                3600
        );
        ReflectionTestUtils.setField(savedRideRecord, "id", 1002L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecordSummary("1", new CreateRideRecordSummaryRequest(
                "web-hud-ride-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600)
        ));

        assertThat(response.rideRecordId()).isEqualTo(1002L);
        assertThat(response.ownerUserId()).isEqualTo(1L);
        assertThat(response.routePointCount()).isZero();
        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
        verify(rideRecordPointRepository, never()).saveAll(any());
        verifyNoInteractions(recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("웹 HUD trace 저장은 기존 요약 기록에 포인트를 저장하고 후처리를 요청한다")
    void saveRideRecordTracePersistsPointsAndRequestsFinalization() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(
                1L,
                "web-hud-ride-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                18250,
                3600
        );
        ReflectionTestUtils.setField(rideRecord, "id", 1002L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(1002L, 1L)).willReturn(Optional.of(rideRecord));
        given(rideRecordPointRepository.countByRideRecordId(1002L)).willReturn(0L);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(rideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecordTrace("1", 1002L, new RideRecordTraceRequest(List.of(
                new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792)),
                new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780))
        )));

        assertThat(response.rideRecordId()).isEqualTo(1002L);
        assertThat(response.routePointCount()).isEqualTo(2);
        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
        verify(rideRecordPointRepository).saveAll(org.mockito.ArgumentMatchers.argThat(points -> {
            java.util.List<com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity> savedPoints = new java.util.ArrayList<>();
            points.forEach(savedPoints::add);
            return savedPoints.size() == 2
                    && savedPoints.get(0).getPointOrder().equals(1)
                    && savedPoints.get(1).getPointOrder().equals(2);
        }));
        verify(recentLocationCacheService).saveCompleted(
                eq("1"),
                eq(1002L),
                eq(2),
                eq(BigDecimal.valueOf(37.5671)),
                eq(BigDecimal.valueOf(126.9792)),
                eq(OffsetDateTime.parse("2026-03-29T11:00:00+09:00"))
        );
        verify(rideRecordFinalizationService).requestFinalization(1002L);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 소유자와 route point를 함께 저장한다")
    void saveRideRecordReturnsPersistedRecordResponse() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(1L, OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(savedRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        ));

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.ownerUserId()).isEqualTo(1L);
        assertThat(response.routePointCount()).isEqualTo(2);
        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
        verify(recentLocationCacheService).saveCompleted(
                eq("1"),
                eq(1001L),
                eq(2),
                eq(BigDecimal.valueOf(37.5671)),
                eq(BigDecimal.valueOf(126.9792)),
                eq(OffsetDateTime.parse("2026-03-29T11:00:00+09:00"))
        );
        verify(rideRecordFinalizationService).requestFinalization(1001L);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 같은 clientRideId 재시도면 기존 기록을 반환하고 중복 저장하지 않는다")
    void saveRideRecordReturnsExistingRecordForSameClientRideId() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity existingRideRecord = new RideRecordEntity(
                1L,
                "android-ride-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                18250,
                3600
        );
        ReflectionTestUtils.setField(existingRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByOwnerUserIdAndClientRideId(1L, "android-ride-001")).willReturn(Optional.of(existingRideRecord));
        given(rideRecordPointRepository.countByRideRecordId(1001L)).willReturn(2L);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                "android-ride-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        ));

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.routePointCount()).isEqualTo(2);
        verifyNoInteractions(recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 같은 clientRideId 동시 저장 경쟁에서 기존 기록을 200 응답으로 복구한다")
    void saveRideRecordReturnsExistingRecordAfterConcurrentClientRideIdRace() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity existingRideRecord = new RideRecordEntity(
                1L,
                "android-ride-race-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                18250,
                3600
        );
        ReflectionTestUtils.setField(existingRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByOwnerUserIdAndClientRideId(1L, "android-ride-race-001"))
                .willReturn(Optional.empty(), Optional.empty(), Optional.of(existingRideRecord));
        given(rideRecordRepository.save(any(RideRecordEntity.class)))
                .willThrow(new DataIntegrityViolationException("uq_ride_records_owner_client_ride_id"));
        given(rideRecordPointRepository.countByRideRecordId(1001L)).willReturn(2L);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                "android-ride-race-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        ));

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.routePointCount()).isEqualTo(2);
        assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
        verify(idempotencyLockService).executeOrWait(
                eq("ride_record_save_full"),
                argThat(key -> key.startsWith("ride-record:1:")
                        && !key.contains("android-ride-race-001")
                        && key.length() == "ride-record:1:".length() + 64),
                any(),
                any()
        );
        verifyNoInteractions(recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("자유 주행 신규 저장은 DB transaction 전에 저장 전용 gate를 통과해야 한다")
    void saveRideRecordUsesRideSaveGateBeforeTransaction() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        doThrow(new RetryableServiceUnavailableException("busy", "RIDE_SAVE_BUSY", 3))
                .when(rideSaveConcurrencyGate).execute(any());

        assertThatThrownBy(() -> rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                "android-ride-gate-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(
                        new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new RideRecordPointRequest(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        )))
                .isInstanceOf(RetryableServiceUnavailableException.class)
                .hasMessage("busy");

        verify(rideSaveConcurrencyGate).execute(any());
        verify(transactionOperations, never()).execute(any());
        verify(rideRecordRepository).findByOwnerUserIdAndClientRideId(1L, "android-ride-gate-001");
        verify(rideRecordRepository, never()).save(any());
        verify(rideRecordRepository, never()).flush();
        verifyNoInteractions(rideRecordPointRepository,
                recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 telemetry nullable 필드를 포함해도 저장된다")
    void saveRideRecordAcceptsTelemetryFields() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity savedRideRecord = new RideRecordEntity(1L, OffsetDateTime.parse("2026-03-29T10:00:00+09:00"), OffsetDateTime.parse("2026-03-29T11:00:00+09:00"), 18250, 3600);
        ReflectionTestUtils.setField(savedRideRecord, "id", 1001L);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.save(any(RideRecordEntity.class))).willReturn(savedRideRecord);

        RideRecordResponse response = rideRecordService.saveRideRecord("1", new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                new RideRecordSummaryRequest(18250, 3600),
                List.of(new RideRecordPointRequest(
                        1,
                        BigDecimal.valueOf(37.5665),
                        BigDecimal.valueOf(126.9780),
                        OffsetDateTime.parse("2026-03-29T10:00:02+09:00"),
                        BigDecimal.valueOf(8.50),
                        BigDecimal.valueOf(5.20),
                        BigDecimal.valueOf(184.0),
                        BigDecimal.valueOf(13.5),
                        BigDecimal.valueOf(2.5),
                        BigDecimal.valueOf(12.5)
                ))
        ));

        assertThat(response.routePointCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 route point 위도 범위를 검증한다")
    void saveRideRecordRejectsInvalidLatitude() {
        CreateRideRecordRequest request = new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T10:10:00+09:00"),
                new RideRecordSummaryRequest(1200, 600),
                List.of(new RideRecordPointRequest(1, BigDecimal.valueOf(-91), BigDecimal.valueOf(126.9780)))
        );

        assertThatThrownBy(() -> rideRecordService.saveRideRecord("1", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("routePoints.latitude는 -90 이상 90 이하여야 합니다.");

        verifyNoInteractions(authService, rideRecordRepository, rideRecordPointRepository,
                recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("자유 주행 기록 저장은 route point 경도 범위를 검증한다")
    void saveRideRecordRejectsInvalidLongitude() {
        CreateRideRecordRequest request = new CreateRideRecordRequest(
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T10:10:00+09:00"),
                new RideRecordSummaryRequest(1200, 600),
                List.of(new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(-181)))
        );

        assertThatThrownBy(() -> rideRecordService.saveRideRecord("1", request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("routePoints.longitude는 -180 이상 180 이하여야 합니다.");

        verifyNoInteractions(authService, rideRecordRepository, rideRecordPointRepository,
                recentLocationCacheService, rideRecordFinalizationService);
    }

    @Test
    @DisplayName("자유 주행 기록 상세 조회는 내 기록이 없으면 NotFoundException을 던진다")
    void getRideRecordStatusThrowsNotFoundWhenOwnedRecordMissing() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(999L, 1L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> rideRecordService.getRideRecordStatus("1", 999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("자유 주행 기록을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("clientRideId 영수증 조회는 내 기록의 후처리 상태와 연결 코스를 반환한다")
    void getRideRecordStatusByClientRideIdReturnsOwnedReceipt() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(
                1L,
                "android-ride-recovery-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                18250,
                3600
        );
        ReflectionTestUtils.setField(rideRecord, "id", 1001L);
        CourseEntity linkedCourse = org.mockito.Mockito.mock(CourseEntity.class);

        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByOwnerUserIdAndClientRideId(1L, "android-ride-recovery-001"))
                .willReturn(Optional.of(rideRecord));
        given(rideRecordFinalizationService.getStatus(rideRecord))
                .willReturn(new RideRecordFinalizationStatusResponse(1001L, "READY", 4, 4, 1, null));
        given(courseRepository.findTopByOwnerUserIdAndSourceRideRecordIdOrderByIdDesc(1L, 1001L))
                .willReturn(Optional.of(linkedCourse));
        given(linkedCourse.getId()).willReturn(2001L);

        RideRecordFinalizationStatusResponse response = rideRecordService
                .getRideRecordStatusByClientRideId("1", " android-ride-recovery-001 ");

        assertThat(response.rideRecordId()).isEqualTo(1001L);
        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.linkedCourseId()).isEqualTo(2001L);
        verify(rideRecordRepository)
                .findByOwnerUserIdAndClientRideId(1L, "android-ride-recovery-001");
    }

    @Test
    @DisplayName("clientRideId 영수증 조회는 미존재와 타 사용자 기록을 같은 404로 숨긴다")
    void getRideRecordStatusByClientRideIdHidesMissingAndOtherOwners() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByOwnerUserIdAndClientRideId(1L, "other-owner-ride"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> rideRecordService.getRideRecordStatusByClientRideId("1", "other-owner-ride"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("자유 주행 기록을 찾을 수 없습니다.");

        verify(rideRecordRepository).findByOwnerUserIdAndClientRideId(1L, "other-owner-ride");
    }

    @Test
    @DisplayName("clientRideId 영수증 조회는 공백과 80자 초과 입력을 DB 조회 전에 거절한다")
    void getRideRecordStatusByClientRideIdRejectsInvalidInputBeforeLookup() {
        assertThatThrownBy(() -> rideRecordService.getRideRecordStatusByClientRideId("1", "   "))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("clientRideId는 비어 있을 수 없습니다.");
        assertThatThrownBy(() -> rideRecordService.getRideRecordStatusByClientRideId("1", "r".repeat(81)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("clientRideId는 80자 이하여야 합니다.");

        verifyNoInteractions(authService, rideRecordRepository);
    }

    @Test
    @DisplayName("자유 주행 기록 재처리는 내 기록이 없으면 NotFoundException을 던진다")
    void regenerateRideRecordThrowsNotFoundWhenOwnedRecordMissing() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(999L, 1L)).willReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> rideRecordService.regenerateRideRecord("1", 999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("자유 주행 기록을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("자유 주행 기록 재처리는 raw point가 없으면 BadRequestException을 던진다")
    void regenerateRideRecordRejectsSummaryOnlyRecord() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        RideRecordEntity rideRecord = new RideRecordEntity(
                1L,
                "web-hud-ride-001",
                OffsetDateTime.parse("2026-03-29T10:00:00+09:00"),
                OffsetDateTime.parse("2026-03-29T11:00:00+09:00"),
                18250,
                3600
        );
        ReflectionTestUtils.setField(rideRecord, "id", 1002L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(rideRecordRepository.findByIdAndOwnerUserId(1002L, 1L)).willReturn(Optional.of(rideRecord));
        given(rideRecordPointRepository.countByRideRecordId(1002L)).willReturn(0L);

        assertThatThrownBy(() -> rideRecordService.regenerateRideRecord("1", 1002L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("trace가 없는 자유 주행 기록은 재처리할 수 없습니다.");

        verifyNoInteractions(rideRecordFinalizationService);
    }
}
