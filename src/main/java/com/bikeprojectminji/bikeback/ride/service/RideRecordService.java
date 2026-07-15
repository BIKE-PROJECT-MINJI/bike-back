package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ConflictException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.idempotency.IdempotencyLockService;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheService;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordListItemResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordListResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordTraceRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
public class RideRecordService {

    private static final Logger log = LoggerFactory.getLogger(RideRecordService.class);
    private static final int CLIENT_RIDE_ID_MAX_LENGTH = 80;

    private final AuthService authService;
    private final CourseRepository courseRepository;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RecentLocationCacheService recentLocationCacheService;
    private final RideRecordFinalizationService rideRecordFinalizationService;
    private final TransactionOperations transactionOperations;
    private final IdempotencyLockService idempotencyLockService;
    private final RideSaveConcurrencyGate rideSaveConcurrencyGate;

    public RideRecordService(
            AuthService authService,
            CourseRepository courseRepository,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RecentLocationCacheService recentLocationCacheService,
            RideRecordFinalizationService rideRecordFinalizationService,
            TransactionOperations transactionOperations,
            IdempotencyLockService idempotencyLockService,
            RideSaveConcurrencyGate rideSaveConcurrencyGate
    ) {
        this.authService = authService;
        this.courseRepository = courseRepository;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.recentLocationCacheService = recentLocationCacheService;
        this.rideRecordFinalizationService = rideRecordFinalizationService;
        this.transactionOperations = transactionOperations;
        this.idempotencyLockService = idempotencyLockService;
        this.rideSaveConcurrencyGate = rideSaveConcurrencyGate;
    }

    @MeasuredOperation("ride.record.save_full")
    public RideRecordResponse saveRideRecord(String subject, CreateRideRecordRequest request) {
        // 자유 주행 저장은 입력 검증 -> 현재 사용자 식별 -> ride record 저장 -> route point 저장 순서로 진행한다.
        // point와 summary는 항상 DB가 원본이고, 캐시는 후속 조회 최적화 용도로만 갱신한다.
        RideRecordRequestValidator.validateCreateRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        String clientRideId = normalizeClientRideId(request.clientRideId());
        return idempotencyLockService.executeOrWait(
                "ride_record_save_full",
                rideRecordIdempotencyKey(user.getId(), clientRideId),
                () -> findExistingRideRecordResponse(user.getId(), clientRideId),
                () -> saveRideRecordWithDuplicateRecovery(subject, user, clientRideId, request)
        );
    }

    private RideRecordResponse saveRideRecordWithDuplicateRecovery(String subject, UserEntity user, String clientRideId, CreateRideRecordRequest request) {
        return rideSaveConcurrencyGate.execute(() -> {
            try {
                return requireTransactionResponse(transactionOperations.execute(status -> createRideRecord(subject, user, clientRideId, request)));
            } catch (DataIntegrityViolationException exception) {
                return findExistingRideRecordResponse(user.getId(), clientRideId)
                        .orElseThrow(() -> exception);
            }
        });
    }

    private RideRecordResponse createRideRecord(String subject, UserEntity user, String clientRideId, CreateRideRecordRequest request) {
        Optional<RideRecordResponse> existingResponse = findExistingRideRecordResponse(user.getId(), clientRideId);
        if (existingResponse.isPresent()) {
            return existingResponse.get();
        }
        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                user.getId(),
                clientRideId,
                request.startedAt(),
                request.endedAt(),
                request.summary().distanceM(),
                request.summary().durationSec()
        ));
        rideRecordRepository.flush();

        rideRecord.markFinalizing(OffsetDateTime.now());
        rideRecord = rideRecordRepository.save(rideRecord);
        Long rideRecordId = rideRecord.getId();

        List<RideRecordPointRequest> normalizedRoutePoints = RideRecordRequestValidator.normalizeRoutePoints(request.routePoints());
        List<RideRecordPointEntity> routePoints = RideRecordPointFactory.createRoutePointEntities(rideRecordId, normalizedRoutePoints);
        rideRecordPointRepository.saveAll(routePoints);
        cacheLatestCompletedLocation(subject, rideRecordId, routePoints, request.endedAt());
        registerFinalizationAfterCommit(rideRecordId);
        log.info("ride record saved subject={} rideRecordId={} routePointCount={} startedAt={} endedAt={}",
                subject, rideRecordId, routePoints.size(), request.startedAt(), request.endedAt());

        return new RideRecordResponse(rideRecordId, user.getId(), routePoints.size(), rideRecord.getFinalizationStatus().name());
    }

    @MeasuredOperation("ride.record.save_summary")
    public RideRecordResponse saveRideRecordSummary(String subject, CreateRideRecordSummaryRequest request) {
        // 웹 HUD 기본 저장은 요약만 남긴다. trace가 없으면 후처리 대상 raw point가 없어 finalization을 시작하지 않는다.
        RideRecordRequestValidator.validateSummaryRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        String clientRideId = normalizeClientRideId(request.clientRideId());
        return idempotencyLockService.executeOrWait(
                "ride_record_save_summary",
                rideRecordIdempotencyKey(user.getId(), clientRideId),
                () -> findExistingRideRecordResponse(user.getId(), clientRideId),
                () -> saveRideRecordSummaryWithDuplicateRecovery(user, clientRideId, request)
        );
    }

    private RideRecordResponse saveRideRecordSummaryWithDuplicateRecovery(UserEntity user, String clientRideId, CreateRideRecordSummaryRequest request) {
        try {
            return requireTransactionResponse(transactionOperations.execute(status -> createRideRecordSummary(user, clientRideId, request)));
        } catch (DataIntegrityViolationException exception) {
            return findExistingRideRecordResponse(user.getId(), clientRideId)
                    .orElseThrow(() -> exception);
        }
    }

    private RideRecordResponse createRideRecordSummary(UserEntity user, String clientRideId, CreateRideRecordSummaryRequest request) {
        Optional<RideRecordResponse> existingResponse = findExistingRideRecordResponse(user.getId(), clientRideId);
        if (existingResponse.isPresent()) {
            return existingResponse.get();
        }
        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                user.getId(),
                clientRideId,
                request.startedAt(),
                request.endedAt(),
                request.summary().distanceM(),
                request.summary().durationSec()
        ));
        rideRecordRepository.flush();

        return new RideRecordResponse(rideRecord.getId(), user.getId(), 0, rideRecord.getFinalizationStatus().name());
    }

    @MeasuredOperation("ride.record.trace_save")
    @Transactional
    public RideRecordResponse saveRideRecordTrace(String subject, Long rideRecordId, RideRecordTraceRequest request) {
        if (request == null) {
            throw new BadRequestException("자유 주행 trace 요청 본문이 필요합니다.");
        }
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(rideRecordId, user.getId())
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        if (rideRecordPointRepository.countByRideRecordId(rideRecordId) > 0) {
            throw new ConflictException("이미 trace가 저장된 자유 주행 기록입니다.");
        }

        List<RideRecordPointRequest> normalizedRoutePoints = RideRecordRequestValidator.normalizeRoutePoints(request.routePoints());
        List<RideRecordPointEntity> routePoints = RideRecordPointFactory.createRoutePointEntities(rideRecordId, normalizedRoutePoints);
        rideRecordPointRepository.saveAll(routePoints);
        rideRecord.markFinalizing(OffsetDateTime.now());
        rideRecordRepository.save(rideRecord);
        cacheLatestCompletedLocation(subject, rideRecordId, routePoints, rideRecord.getEndedAt());
        registerFinalizationAfterCommit(rideRecordId);

        return new RideRecordResponse(rideRecordId, user.getId(), routePoints.size(), rideRecord.getFinalizationStatus().name());
    }

    @MeasuredOperation("ride.record.list")
    @Transactional(readOnly = true)
    public RideRecordListResponse listRideRecords(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        List<RideRecordEntity> rideRecords = rideRecordRepository.findTop20ByOwnerUserIdOrderByEndedAtDescIdDesc(user.getId());
        Map<Long, Long> linkedCourseIds = resolveLinkedCourseIds(user.getId(), rideRecords);

        return new RideRecordListResponse(rideRecords.stream()
                .map(rideRecord -> new RideRecordListItemResponse(
                        rideRecord.getId(),
                        rideRecord.getStartedAt(),
                        rideRecord.getEndedAt(),
                        rideRecord.getDistanceM(),
                        rideRecord.getDurationSec(),
                        rideRecord.getFinalizationStatus().name(),
                        linkedCourseIds.get(rideRecord.getId())
                ))
                .toList());
    }

    @MeasuredOperation("ride.record.status")
    @Transactional(readOnly = true)
    public RideRecordFinalizationStatusResponse getRideRecordStatus(String subject, Long rideRecordId) {
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(rideRecordId, user.getId())
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        return toRideRecordFinalizationStatusResponse(user.getId(), rideRecord);
    }

    @MeasuredOperation("ride.record.status_by_client_ride_id")
    @Transactional(readOnly = true)
    public RideRecordFinalizationStatusResponse getRideRecordStatusByClientRideId(
            String subject,
            String clientRideId
    ) {
        String normalizedClientRideId = validateRecoveryClientRideId(clientRideId);
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository
                .findByOwnerUserIdAndClientRideId(user.getId(), normalizedClientRideId)
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        return toRideRecordFinalizationStatusResponse(user.getId(), rideRecord);
    }

    @MeasuredOperation("ride.record.regenerate")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RideRecordFinalizationStatusResponse regenerateRideRecord(String subject, Long rideRecordId) {
        UserEntity user = authService.findUserBySubject(subject);
        return idempotencyLockService.executeOrWaitAfterContention(
                "ride_record_regenerate",
                rideRecordRegenerateIdempotencyKey(user.getId(), rideRecordId),
                () -> findRideRecordStatusResponse(user.getId(), rideRecordId),
                () -> regenerateRideRecordWithLock(user.getId(), rideRecordId)
        );
    }

    private RideRecordFinalizationStatusResponse regenerateRideRecordWithLock(Long ownerUserId, Long rideRecordId) {
        return requireFinalizationTransactionResponse(transactionOperations.execute(status -> {
            RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(rideRecordId, ownerUserId)
                    .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
            if (rideRecordPointRepository.countByRideRecordId(rideRecordId) == 0) {
                throw new BadRequestException("trace가 없는 자유 주행 기록은 재처리할 수 없습니다.");
            }
            rideRecordFinalizationService.markForRegeneration(rideRecord);
            registerFinalizationAfterCommit(rideRecordId);
            return toRideRecordFinalizationStatusResponse(ownerUserId, rideRecord);
        }));
    }

    private void cacheLatestCompletedLocation(
            String subject,
            Long rideRecordId,
            List<RideRecordPointEntity> routePoints,
            OffsetDateTime endedAt
    ) {
        // 최종 기록은 DB가 원본이고, 최근 위치 조회만 빠르게 하기 위해 마지막 포인트를 보조 캐시에 남긴다.
        if (routePoints.isEmpty()) {
            return;
        }
        RideRecordPointEntity latestPoint = routePoints.get(routePoints.size() - 1);
        recentLocationCacheService.saveCompleted(
                subject,
                rideRecordId,
                latestPoint.getPointOrder(),
                latestPoint.getLatitude(),
                latestPoint.getLongitude(),
                endedAt
        );
    }

    private Long findLinkedCourseId(Long ownerUserId, Long rideRecordId) {
        return courseRepository.findTopByOwnerUserIdAndSourceRideRecordIdOrderByIdDesc(ownerUserId, rideRecordId)
                .map(CourseEntity::getId)
                .orElse(null);
    }

    private Optional<RideRecordFinalizationStatusResponse> findRideRecordStatusResponse(Long ownerUserId, Long rideRecordId) {
        return rideRecordRepository.findByIdAndOwnerUserId(rideRecordId, ownerUserId)
                .map(rideRecord -> toRideRecordFinalizationStatusResponse(ownerUserId, rideRecord));
    }

    private RideRecordFinalizationStatusResponse toRideRecordFinalizationStatusResponse(Long ownerUserId, RideRecordEntity rideRecord) {
        RideRecordFinalizationStatusResponse status = rideRecordFinalizationService.getStatus(rideRecord);
        return new RideRecordFinalizationStatusResponse(
                status.rideRecordId(),
                status.status(),
                status.rawPointCount(),
                status.processedPointCount(),
                status.finalizationAttempts(),
                status.errorMessage(),
                rideRecord.getStartedAt(),
                rideRecord.getEndedAt(),
                rideRecord.getDistanceM(),
                rideRecord.getDurationSec(),
                findLinkedCourseId(ownerUserId, rideRecord.getId())
        );
    }

    private Map<Long, Long> resolveLinkedCourseIds(Long ownerUserId, List<RideRecordEntity> rideRecords) {
        Map<Long, Long> linkedCourseIds = new HashMap<>();
        if (rideRecords.isEmpty()) {
            return linkedCourseIds;
        }

        List<Long> rideRecordIds = rideRecords.stream()
                .map(RideRecordEntity::getId)
                .toList();

        for (CourseEntity course : courseRepository.findByOwnerUserIdAndSourceRideRecordIdIn(ownerUserId, rideRecordIds)) {
            if (course.getSourceRideRecordId() == null) {
                continue;
            }
            linkedCourseIds.putIfAbsent(course.getSourceRideRecordId(), course.getId());
        }
        return linkedCourseIds;
    }

    private String normalizeClientRideId(String clientRideId) {
        if (clientRideId == null || clientRideId.isBlank()) {
            return null;
        }
        return clientRideId.trim();
    }

    private String validateRecoveryClientRideId(String clientRideId) {
        if (clientRideId == null || clientRideId.isBlank()) {
            throw new BadRequestException("clientRideId는 비어 있을 수 없습니다.");
        }
        String normalizedClientRideId = clientRideId.trim();
        if (normalizedClientRideId.length() > CLIENT_RIDE_ID_MAX_LENGTH) {
            throw new BadRequestException("clientRideId는 80자 이하여야 합니다.");
        }
        return normalizedClientRideId;
    }

    private Optional<RideRecordResponse> findExistingRideRecordResponse(Long ownerUserId, String clientRideId) {
        if (clientRideId == null) {
            return Optional.empty();
        }
        return rideRecordRepository.findByOwnerUserIdAndClientRideId(ownerUserId, clientRideId)
                .map(existing -> {
                    long routePointCount = rideRecordPointRepository.countByRideRecordId(existing.getId());
                    return new RideRecordResponse(
                            existing.getId(),
                            existing.getOwnerUserId(),
                            Math.toIntExact(routePointCount),
                            existing.getFinalizationStatus().name()
                    );
                });
    }

    private String rideRecordIdempotencyKey(Long ownerUserId, String clientRideId) {
        if (ownerUserId == null || clientRideId == null) {
            return null;
        }
        return "ride-record:" + ownerUserId + ":" + sha256(clientRideId);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String rideRecordRegenerateIdempotencyKey(Long ownerUserId, Long rideRecordId) {
        if (ownerUserId == null || rideRecordId == null) {
            return null;
        }
        return "ride-record-regenerate:" + ownerUserId + ":" + rideRecordId;
    }

    private RideRecordResponse requireTransactionResponse(RideRecordResponse response) {
        return Objects.requireNonNull(response, "ride record transaction response must not be null");
    }

    private RideRecordFinalizationStatusResponse requireFinalizationTransactionResponse(RideRecordFinalizationStatusResponse response) {
        return Objects.requireNonNull(response, "ride record finalization transaction response must not be null");
    }

    private void registerFinalizationAfterCommit(Long rideRecordId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rideRecordFinalizationService.requestFinalization(rideRecordId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rideRecordFinalizationService.requestFinalization(rideRecordId);
            }
        });
    }

}
