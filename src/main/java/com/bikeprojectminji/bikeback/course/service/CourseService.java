package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.achievement.service.AchievementCompletionSignal;
import com.bikeprojectminji.bikeback.achievement.service.AchievementRoutePoint;
import com.bikeprojectminji.bikeback.achievement.service.AchievementCompletionDispatcher;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.ImportGpxCourseRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseVisibilityRequest;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRouteQualityStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.idempotency.IdempotencyLockService;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import com.bikeprojectminji.bikeback.global.validation.CoordinateValidator;
import java.math.RoundingMode;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRouteGeometryRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Service
@Transactional
public class CourseService {

    private static final Duration COURSE_FROM_RIDE_IDEMPOTENCY_WAIT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration COURSE_FROM_RIDE_IDEMPOTENCY_MIN_WAIT_INTERVAL = Duration.ofMillis(300);
    private static final Duration COURSE_FROM_RIDE_IDEMPOTENCY_MAX_WAIT_INTERVAL = Duration.ofMillis(500);

    private final CourseRepository courseRepository;
    private final CourseRouteGeometryRepository courseRouteGeometryRepository;
    private final CourseRoutePointRepository courseRoutePointRepository;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final AuthService authService;
    private final CourseRouteSnapshotService courseRouteSnapshotService;
    private final AchievementCompletionDispatcher achievementCompletionDispatcher;
    private final TransactionOperations transactionOperations;
    private final IdempotencyLockService idempotencyLockService;
    private final CourseAccessPolicy courseAccessPolicy = new CourseAccessPolicy();
    private final GpxTrackParser gpxTrackParser = new GpxTrackParser();

    public CourseService(
            CourseRepository courseRepository,
            CourseRouteGeometryRepository courseRouteGeometryRepository,
            CourseRoutePointRepository courseRoutePointRepository,
            RideRecordRepository rideRecordRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            AuthService authService,
            CourseRouteSnapshotService courseRouteSnapshotService,
            AchievementCompletionDispatcher achievementCompletionDispatcher,
            TransactionOperations transactionOperations,
            IdempotencyLockService idempotencyLockService
    ) {
        this.courseRepository = courseRepository;
        this.courseRouteGeometryRepository = courseRouteGeometryRepository;
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.authService = authService;
        this.courseRouteSnapshotService = courseRouteSnapshotService;
        this.achievementCompletionDispatcher = achievementCompletionDispatcher;
        this.transactionOperations = transactionOperations;
        this.idempotencyLockService = idempotencyLockService;
    }

    @MeasuredOperation("course.write.from_ride_record")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CourseWriteResponse createCourseFromRideRecord(String subject, CreateCourseFromRideRecordRequest request) {
        // 코스 생성은 사용자가 소유한 ride record를 읽어 route point를 course route point로 복제하는 방식이다.
        // 즉 ride 기록과 course는 source of truth를 공유하지 않고, 생성 시점에 복사본을 만든다.
        validateCreateRequest(request);
        Long ownerUserId = resolveOwnerUserIdForIdempotency(subject);
        return idempotencyLockService.executeOrWaitAfterContention(
                "course_from_ride",
                courseFromRideIdempotencyKey(ownerUserId, request.sourceRideRecordId()),
                COURSE_FROM_RIDE_IDEMPOTENCY_WAIT_TIMEOUT,
                COURSE_FROM_RIDE_IDEMPOTENCY_MIN_WAIT_INTERVAL,
                COURSE_FROM_RIDE_IDEMPOTENCY_MAX_WAIT_INTERVAL,
                () -> findExistingSourceCourseResponse(ownerUserId, request.sourceRideRecordId()),
                () -> createCourseFromRideRecordWithDuplicateRecovery(subject, ownerUserId, request)
        );
    }

    private CourseWriteResponse createCourseFromRideRecordWithDuplicateRecovery(String subject, Long ownerUserId, CreateCourseFromRideRecordRequest request) {
        UserEntity user = authService.findUserBySubject(subject);
        if (!Objects.equals(user.getId(), ownerUserId)) {
            throw new NotFoundException("자유 주행 기록을 찾을 수 없습니다.");
        }
        RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(request.sourceRideRecordId(), user.getId())
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        if (rideRecord.getFinalizationStatus() != RideRecordFinalizationStatus.READY) {
            throw new BadRequestException("경로 보정이 아직 완료되지 않았습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (rideRecord.getQualityStatus() != RideRouteQualityStatus.FULL
                && rideRecord.getQualityStatus() != RideRouteQualityStatus.PARTIAL) {
            throw new BadRequestException("GPS 품질 검증이 완료되지 않아 코스를 생성할 수 없습니다. 원본 기록은 보존됩니다.");
        }
        try {
            return requireTransactionResponse(transactionOperations.execute(status -> createCourseFromRideRecord(user, rideRecord, request)));
        } catch (DataIntegrityViolationException exception) {
            return findExistingSourceCourseResponse(user.getId(), rideRecord.getId())
                    .orElseThrow(() -> exception);
        }
    }

    private Long resolveOwnerUserIdForIdempotency(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            return authService.findUserBySubject(subject).getId();
        }
    }

    private CourseWriteResponse createCourseFromRideRecord(UserEntity user, RideRecordEntity rideRecord, CreateCourseFromRideRecordRequest request) {
        Optional<CourseWriteResponse> existingResponse = findExistingSourceCourseResponse(user.getId(), rideRecord.getId());
        if (existingResponse.isPresent()) {
            return existingResponse.get();
        }
        List<RideRecordProcessedPointEntity> rideRecordPoints = rideRecordProcessedPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecord.getId());
        if (rideRecordPoints.isEmpty()) {
            throw new BadRequestException("최종 경로가 비어 있어 코스를 생성할 수 없습니다.");
        }

        CourseVisibility visibility = parseVisibility(request.visibility());
        long processedDistanceM = distanceMeters(rideRecordPoints.stream()
                .map(point -> new CourseRoutePointRequest(
                        point.getPointOrder(),
                        point.getLatitude(),
                        point.getLongitude()
                ))
                .toList());
        CourseEntity course = new CourseEntity(
                request.name(),
                request.description(),
                toDistanceKm(processedDistanceM),
                toDurationMin(rideRecord.getDurationSec()),
                resolveNextDisplayOrder(),
                false,
                null,
                rideRecordPoints.get(0).getLatitude(),
                rideRecordPoints.get(0).getLongitude(),
                user.getId(),
                rideRecord.getId(),
                visibility
        );
        CourseEntity savedCourse = courseRepository.save(course);
        courseRepository.flush();

        List<CourseRoutePointEntity> courseRoutePoints = rideRecordPoints.stream()
                .map(point -> new CourseRoutePointEntity(savedCourse.getId(), point.getPointOrder(), point.getLatitude(), point.getLongitude()))
                .toList();
        courseRoutePointRepository.saveAll(courseRoutePoints);
        courseRoutePointRepository.flush();
        courseRouteGeometryRepository.refreshRouteLine(savedCourse.getId());
        courseRouteSnapshotService.evict(savedCourse.getId(), "course_created");
        achievementCompletionDispatcher.dispatchAfterCommit(new AchievementCompletionSignal(
                user.getId(),
                savedCourse.getId(),
                rideRecord.getId(),
                courseRoutePoints.stream()
                        .map(point -> new AchievementRoutePoint(point.getLatitude(), point.getLongitude()))
                        .toList()
        ));

        return toCourseWriteResponse(savedCourse);
    }

    @MeasuredOperation("course.write.import_gpx")
    public CourseWriteResponse importGpxCourse(String subject, ImportGpxCourseRequest request) {
        validateImportGpxRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        List<CourseRoutePointRequest> routePoints = normalizeRoutePoints(gpxTrackParser.parse(request.gpx()));
        BigDecimal distanceKm = toDistanceKm(distanceMeters(routePoints));
        Integer durationMin = estimateDurationMin(distanceKm);
        CourseVisibility visibility = parseVisibility(request.visibility());

        CourseEntity course = new CourseEntity(
                request.title().trim(),
                request.description(),
                distanceKm,
                durationMin,
                resolveNextDisplayOrder(),
                false,
                null,
                routePoints.get(0).latitude(),
                routePoints.get(0).longitude(),
                user.getId(),
                visibility
        );
        CourseEntity savedCourse = courseRepository.save(course);
        courseRoutePointRepository.saveAll(routePoints.stream()
                .map(point -> new CourseRoutePointEntity(savedCourse.getId(), point.pointOrder(), point.latitude(), point.longitude()))
                .toList());
        courseRoutePointRepository.flush();
        courseRouteGeometryRepository.refreshRouteLine(savedCourse.getId());
        courseRouteSnapshotService.evict(savedCourse.getId(), "gpx_imported");
        return toCourseWriteResponse(savedCourse);
    }

    @MeasuredOperation("course.write.update")
    public CourseWriteResponse updateCourse(String subject, Long courseId, UpdateCourseRequest request) {
        // 코스 수정은 metadata 수정과 route point 교체를 한 트랜잭션으로 처리해
        // 제목/설명/visibility와 경로 포인트가 어긋난 상태를 남기지 않게 한다.
        validateUpdateRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        CourseEntity course = findOwnedCourse(courseId, user.getId());
        CourseVisibility visibility = parseVisibility(request.visibility());
        course.updateMetadata(request.name(), request.description(), visibility);

        if (request.routePoints() != null) {
            List<CourseRoutePointRequest> routePoints = normalizeRoutePoints(request.routePoints());
            courseRoutePointRepository.deleteByCourseId(courseId);
            courseRoutePointRepository.flush();
            courseRoutePointRepository.saveAll(routePoints.stream()
                    .map(point -> new CourseRoutePointEntity(courseId, point.pointOrder(), point.latitude(), point.longitude()))
                    .toList());
            courseRoutePointRepository.flush();
            course.updateStartCoordinates(routePoints.get(0).latitude(), routePoints.get(0).longitude());
            courseRouteGeometryRepository.refreshRouteLine(courseId);
            courseRouteSnapshotService.evict(courseId, "route_points_updated");
        }

        return toCourseWriteResponse(courseRepository.save(course));
    }

    @MeasuredOperation("course.write.visibility")
    public CourseWriteResponse updateCourseVisibility(String subject, Long courseId, UpdateCourseVisibilityRequest request) {
        if (request == null || request.visibility() == null || request.visibility().isBlank()) {
            throw new BadRequestException("visibility는 비어 있을 수 없습니다.");
        }
        UserEntity user = authService.findUserBySubject(subject);
        CourseEntity course = findOwnedCourse(courseId, user.getId());
        course.updateMetadata(course.getTitle(), course.getDescription(), parseVisibility(request.visibility()));
        return toCourseWriteResponse(courseRepository.save(course));
    }

    @MeasuredOperation("course.share.info")
    public CourseShareResponse getCourseShareInfo(String subject, Long courseId) {
        // 공유 정보 조회는 PRIVATE 코스를 먼저 차단하고,
        // 공개 가능한 코스에 대해서만 shareToken을 생성 또는 재사용한다.
        UserEntity user = authService.findUserBySubject(subject);
        CourseEntity course = findOwnedCourse(courseId, user.getId());

        if (course.getVisibility() == CourseVisibility.PRIVATE) {
            throw new BadRequestException("PRIVATE 코스는 먼저 공개 범위를 변경한 뒤 공유해야 합니다.");
        }

        if (course.getShareToken() == null || course.getShareToken().isBlank()) {
            course.updateShareToken(UUID.randomUUID().toString().replace("-", ""));
            course = courseRepository.save(course);
        }

        String shareType = course.getVisibility() == CourseVisibility.PUBLIC ? "PUBLIC_LINK" : "UNLISTED_LINK";
        String shareUrl = "/api/v1/courses/" + course.getId() + "?shareToken=" + course.getShareToken();
        return new CourseShareResponse(shareType, course.getVisibility().name(), shareUrl, course.getShareToken());
    }

    private void validateCreateRequest(CreateCourseFromRideRecordRequest request) {
        if (request == null) {
            throw new BadRequestException("코스 생성 요청 본문이 필요합니다.");
        }
        if (request.sourceRideRecordId() == null) {
            throw new BadRequestException("sourceRideRecordId는 비어 있을 수 없습니다.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name은 비어 있을 수 없습니다.");
        }
        if (request.visibility() == null || request.visibility().isBlank()) {
            throw new BadRequestException("visibility는 비어 있을 수 없습니다.");
        }
    }

    private void validateImportGpxRequest(ImportGpxCourseRequest request) {
        if (request == null) {
            throw new BadRequestException("GPX 코스 저장 요청 본문이 필요합니다.");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("title은 비어 있을 수 없습니다.");
        }
        if (request.visibility() == null || request.visibility().isBlank()) {
            throw new BadRequestException("visibility는 비어 있을 수 없습니다.");
        }
    }

    private void validateUpdateRequest(UpdateCourseRequest request) {
        if (request == null) {
            throw new BadRequestException("코스 저장 요청 본문이 필요합니다.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name은 비어 있을 수 없습니다.");
        }
        if (request.visibility() == null || request.visibility().isBlank()) {
            throw new BadRequestException("visibility는 비어 있을 수 없습니다.");
        }
        if (request.routePoints() != null) {
            normalizeRoutePoints(request.routePoints());
        }
    }

    private CourseEntity findOwnedCourse(Long courseId, Long ownerUserId) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        courseAccessPolicy.assertOwned(course, ownerUserId);
        return course;
    }

    private CourseVisibility parseVisibility(String rawVisibility) {
        try {
            return CourseVisibility.valueOf(rawVisibility.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("visibility는 PRIVATE, UNLISTED, PUBLIC 중 하나여야 합니다.");
        }
    }

    private Optional<CourseWriteResponse> findExistingSourceCourseResponse(Long ownerUserId, Long sourceRideRecordId) {
        return courseRepository.findTopByOwnerUserIdAndSourceRideRecordIdOrderByIdDesc(ownerUserId, sourceRideRecordId)
                .map(this::toCourseWriteResponse);
    }

    private String courseFromRideIdempotencyKey(Long ownerUserId, Long sourceRideRecordId) {
        if (ownerUserId == null || sourceRideRecordId == null) {
            return null;
        }
        return "course-from-ride:" + ownerUserId + ":" + sourceRideRecordId;
    }

    private CourseWriteResponse requireTransactionResponse(CourseWriteResponse response) {
        return Objects.requireNonNull(response, "course transaction response must not be null");
    }

    private int resolveNextDisplayOrder() {
        return courseRepository.findTopByOrderByDisplayOrderDescIdDesc()
                .map(course -> course.getDisplayOrder() + 1)
                .orElse(1);
    }

    private BigDecimal toDistanceKm(Integer distanceM) {
        return BigDecimal.valueOf(distanceM)
                .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
    }

    private Integer toDurationMin(Integer durationSec) {
        return BigDecimal.valueOf(durationSec)
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    private BigDecimal toDistanceKm(long distanceM) {
        return BigDecimal.valueOf(distanceM)
                .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
    }

    private Integer estimateDurationMin(BigDecimal distanceKm) {
        return distanceKm
                .divide(BigDecimal.valueOf(15), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(60))
                .setScale(0, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE)
                .intValue();
    }

    private long distanceMeters(List<CourseRoutePointRequest> routePoints) {
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 1; index < routePoints.size(); index++) {
            total = total.add(BigDecimal.valueOf(haversineMeters(routePoints.get(index - 1), routePoints.get(index))));
        }
        return total.setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private double haversineMeters(CourseRoutePointRequest left, CourseRoutePointRequest right) {
        double earthRadiusM = 6371000.0;
        double dLat = Math.toRadians(right.latitude().doubleValue() - left.latitude().doubleValue());
        double dLon = Math.toRadians(right.longitude().doubleValue() - left.longitude().doubleValue());
        double lat1 = Math.toRadians(left.latitude().doubleValue());
        double lat2 = Math.toRadians(right.latitude().doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<CourseRoutePointRequest> normalizeRoutePoints(List<CourseRoutePointRequest> routePoints) {
        if (routePoints.isEmpty()) {
            throw new BadRequestException("routePoints는 비어 있을 수 없습니다.");
        }
        Set<Integer> pointOrders = new HashSet<>();
        for (CourseRoutePointRequest routePoint : routePoints) {
            if (routePoint.pointOrder() == null || routePoint.pointOrder() < 1) {
                throw new BadRequestException("pointOrder는 1 이상이어야 합니다.");
            }
            if (routePoint.latitude() == null || routePoint.longitude() == null) {
                throw new BadRequestException("routePoints의 latitude와 longitude는 비어 있을 수 없습니다.");
            }
            CoordinateValidator.validateLatLon(
                    "routePoints.latitude",
                    routePoint.latitude(),
                    "routePoints.longitude",
                    routePoint.longitude()
            );
            if (!pointOrders.add(routePoint.pointOrder())) {
                throw new BadRequestException("routePoints의 pointOrder는 중복될 수 없습니다.");
            }
        }
        return routePoints.stream()
                .sorted(Comparator.comparing(CourseRoutePointRequest::pointOrder))
                .toList();
    }

    private CourseWriteResponse toCourseWriteResponse(CourseEntity course) {
        return new CourseWriteResponse(
                course.getId(),
                course.getOwnerUserId(),
                course.getVisibility().name(),
                course.getTitle(),
                course.getSourceRideRecordId(),
                course.isSourceDetached()
        );
    }

}
