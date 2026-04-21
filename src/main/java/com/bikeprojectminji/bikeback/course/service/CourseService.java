package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.course.dto.CourseListItemResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseDetailResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseItemResponse;
import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseVisibilityRequest;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import java.math.RoundingMode;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private static final int DEFAULT_LIMIT = 10;
    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private final CourseRepository courseRepository;
    private final CourseRoutePointRepository courseRoutePointRepository;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final AuthService authService;

    public CourseService(
            CourseRepository courseRepository,
            CourseRoutePointRepository courseRoutePointRepository,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            AuthService authService
    ) {
        this.courseRepository = courseRepository;
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.authService = authService;
    }

    public CourseListResponse getCourses(Long cursor, Integer limit) {
        // 전체 코스 목록은 public 코스만 대상으로 cursor pagination을 적용하고,
        // 응답에서는 화면 목록에 필요한 최소 필드만 남긴다.
        int pageSize = resolveLimit(limit);
        List<CourseEntity> queriedCourses = courseRepository.findPublicPageAfter(cursor, pageSize + 1);

        boolean hasNext = queriedCourses.size() > pageSize;
        List<CourseEntity> pageCourses = hasNext ? queriedCourses.subList(0, pageSize) : queriedCourses;

        List<CourseListItemResponse> items = pageCourses.stream()
                .map(course -> new CourseListItemResponse(
                        course.getId(),
                        course.getTitle(),
                        course.getDistanceKm(),
                        course.getEstimatedDurationMin()
                ))
                .toList();

        String nextCursor = hasNext && !pageCourses.isEmpty()
                ? String.valueOf(pageCourses.get(pageCourses.size() - 1).getId())
                : null;

        return new CourseListResponse(items, hasNext, nextCursor);
    }

    public CourseDetailResponse getCourseDetail(Long courseId, String subject, String shareToken) {
        // 코스 상세는 visibility / owner / shareToken 규칙을 통과한 코스만 읽을 수 있다.
        CourseEntity course = findReadableCourse(courseId, subject, shareToken);

        return new CourseDetailResponse(
                course.getId(),
                course.getTitle(),
                course.getDistanceKm(),
                course.getEstimatedDurationMin()
        );
    }

    public CourseRoutePointsResponse getCourseRoutePoints(Long courseId, String subject, String shareToken) {
        // 경로 포인트 조회도 상세 조회와 같은 읽기 권한 규칙을 그대로 따른다.
        CourseEntity course = findReadableCourse(courseId, subject, shareToken);

        List<CourseRoutePointResponse> points = courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(course.getId()).stream()
                .map(routePoint -> new CourseRoutePointResponse(
                        routePoint.getPointOrder(),
                        routePoint.getLatitude(),
                        routePoint.getLongitude()
                ))
                .toList();

        return new CourseRoutePointsResponse(course.getId(), points);
    }

    public FeaturedCourseResponse getFeaturedCourses(BigDecimal lat, BigDecimal lon) {
        // 추천 코스는 curated 후보를 먼저 읽고,
        // 위치가 있으면 거리 기준 정렬, 없으면 fallback 순서로 제한된 개수만 노출한다.
        List<CourseEntity> featuredCourses = courseRepository.findFeaturedCourses();
        if (featuredCourses.isEmpty()) {
            log.info("featured_courses_fallback request_id={} reason=no_curated_courses", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId());
            return new FeaturedCourseResponse("fallback", List.of());
        }

        boolean distanceMode = lat != null && lon != null;
        if (!distanceMode) {
            log.info("featured_courses_fallback request_id={} reason=missing_location_parameters", com.bikeprojectminji.bikeback.global.logging.RequestLogContext.currentRequestId());
        }
        List<FeaturedCourseItemResponse> items = (distanceMode ? featuredCourses.stream()
                .map(course -> toFeaturedResponse(course, lat, lon))
                .sorted(Comparator
                        .comparing(FeaturedCourseItemResponse::distanceFromUserM, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FeaturedCourseItemResponse::featuredRank, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FeaturedCourseItemResponse::id))
                .limit(3)
                .toList() : featuredCourses.stream()
                .map(course -> toFeaturedResponse(course, null, null))
                .limit(3)
                .toList());

        return new FeaturedCourseResponse(distanceMode ? "distance" : "fallback", items);
    }

    public CourseListResponse searchPublicCourses(String query, String sort) {
        validateSearchSort(sort);

        List<CourseEntity> courses = isBlank(query)
                ? courseRepository.findTop20ByVisibilityOrderByIdDesc(CourseVisibility.PUBLIC)
                : courseRepository.findTop20ByVisibilityAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility.PUBLIC, query.trim());

        List<CourseListItemResponse> items = courses.stream()
                .map(course -> new CourseListItemResponse(
                        course.getId(),
                        course.getTitle(),
                        course.getDistanceKm(),
                        course.getEstimatedDurationMin()
                ))
                .toList();

        return new CourseListResponse(items, false, null);
    }

    @Transactional
    public CourseWriteResponse createCourseFromRideRecord(String subject, CreateCourseFromRideRecordRequest request) {
        // 코스 생성은 사용자가 소유한 ride record를 읽어 route point를 course route point로 복제하는 방식이다.
        // 즉 ride 기록과 course는 source of truth를 공유하지 않고, 생성 시점에 복사본을 만든다.
        validateCreateRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository.findByIdAndOwnerUserId(request.sourceRideRecordId(), user.getId())
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        if (rideRecord.getFinalizationStatus() != RideRecordFinalizationStatus.READY) {
            throw new BadRequestException("경로 보정이 아직 완료되지 않았습니다. 잠시 후 다시 시도해 주세요.");
        }
        List<RideRecordProcessedPointEntity> rideRecordPoints = rideRecordProcessedPointRepository.findByRideRecordIdOrderByPointOrderAsc(rideRecord.getId());
        if (rideRecordPoints.isEmpty()) {
            throw new BadRequestException("최종 경로가 비어 있어 코스를 생성할 수 없습니다.");
        }

        CourseVisibility visibility = parseVisibility(request.visibility());
        CourseEntity course = new CourseEntity(
                request.name(),
                request.description(),
                toDistanceKm(rideRecord.getDistanceM()),
                toDurationMin(rideRecord.getDurationSec()),
                resolveNextDisplayOrder(),
                false,
                null,
                rideRecordPoints.get(0).getLatitude(),
                rideRecordPoints.get(0).getLongitude(),
                user.getId(),
                visibility
        );
        CourseEntity savedCourse = courseRepository.save(course);

        List<CourseRoutePointEntity> courseRoutePoints = rideRecordPoints.stream()
                .map(point -> new CourseRoutePointEntity(savedCourse.getId(), point.getPointOrder(), point.getLatitude(), point.getLongitude()))
                .toList();
        courseRoutePointRepository.saveAll(courseRoutePoints);

        return toCourseWriteResponse(savedCourse);
    }

    @Transactional
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
            courseRoutePointRepository.saveAll(routePoints.stream()
                    .map(point -> new CourseRoutePointEntity(courseId, point.pointOrder(), point.latitude(), point.longitude()))
                    .toList());
            course.updateStartCoordinates(routePoints.get(0).latitude(), routePoints.get(0).longitude());
        }

        return toCourseWriteResponse(courseRepository.save(course));
    }

    @Transactional
    public CourseWriteResponse updateCourseVisibility(String subject, Long courseId, UpdateCourseVisibilityRequest request) {
        if (request == null || request.visibility() == null || request.visibility().isBlank()) {
            throw new BadRequestException("visibility는 비어 있을 수 없습니다.");
        }
        UserEntity user = authService.findUserBySubject(subject);
        CourseEntity course = findOwnedCourse(courseId, user.getId());
        course.updateMetadata(course.getTitle(), course.getDescription(), parseVisibility(request.visibility()));
        return toCourseWriteResponse(courseRepository.save(course));
    }

    @Transactional
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

    public CourseDownloadResponse downloadCourse(Long courseId, String subject, String shareToken) {
        CourseEntity course = findReadableCourse(courseId, subject, shareToken);
        List<CourseRoutePointResponse> routePoints = courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(course.getId()).stream()
                .map(routePoint -> new CourseRoutePointResponse(
                        routePoint.getPointOrder(),
                        routePoint.getLatitude(),
                        routePoint.getLongitude()
                ))
                .toList();

        return new CourseDownloadResponse(course.getId(), course.getTitle(), course.getVisibility().name(), routePoints);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return limit;
    }

    private FeaturedCourseItemResponse toFeaturedResponse(CourseEntity course, BigDecimal lat, BigDecimal lon) {
        Integer distanceFromUserM = calculateDistanceFromUserM(course, lat, lon);
        return new FeaturedCourseItemResponse(
                course.getId(),
                course.getTitle(),
                course.getDistanceKm(),
                course.getEstimatedDurationMin(),
                distanceFromUserM,
                course.getFeaturedRank()
        );
    }

    private Integer calculateDistanceFromUserM(CourseEntity course, BigDecimal lat, BigDecimal lon) {
        if (lat == null || lon == null || course.getStartLatitude() == null || course.getStartLongitude() == null) {
            return null;
        }

        double distanceMeters = haversineMeters(
                lat.doubleValue(),
                lon.doubleValue(),
                course.getStartLatitude().doubleValue(),
                course.getStartLongitude().doubleValue()
        );
        return BigDecimal.valueOf(distanceMeters).setScale(0, RoundingMode.HALF_UP).intValue();
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
        if (course.getOwnerUserId() == null || !course.getOwnerUserId().equals(ownerUserId)) {
            throw new ForbiddenException("이 코스를 수정할 권한이 없습니다.");
        }
        return course;
    }

    private CourseEntity findReadableCourse(Long courseId, String subject, String shareToken) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));

        if (course.getVisibility() == CourseVisibility.PUBLIC) {
            return course;
        }

        if (course.getVisibility() == CourseVisibility.UNLISTED) {
            if (!isBlank(shareToken) && shareToken.equals(course.getShareToken())) {
                return course;
            }
            throw new ForbiddenException("이 코스에 접근할 권한이 없습니다.");
        }

        if (subject == null || subject.isBlank()) {
            throw new ForbiddenException("이 코스는 공개되지 않았습니다.");
        }

        UserEntity user = authService.findUserBySubject(subject);
        if (course.getOwnerUserId() == null || !course.getOwnerUserId().equals(user.getId())) {
            throw new ForbiddenException("이 코스는 공개되지 않았습니다.");
        }

        return course;
    }

    private void validateSearchSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return;
        }
        if (!"latest".equalsIgnoreCase(sort)) {
            throw new BadRequestException("sort는 latest만 지원합니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private CourseVisibility parseVisibility(String rawVisibility) {
        try {
            return CourseVisibility.valueOf(rawVisibility.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("visibility는 PRIVATE, UNLISTED, PUBLIC 중 하나여야 합니다.");
        }
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
            if (!pointOrders.add(routePoint.pointOrder())) {
                throw new BadRequestException("routePoints의 pointOrder는 중복될 수 없습니다.");
            }
        }
        return routePoints.stream()
                .sorted(Comparator.comparing(CourseRoutePointRequest::pointOrder))
                .toList();
    }

    private CourseWriteResponse toCourseWriteResponse(CourseEntity course) {
        return new CourseWriteResponse(course.getId(), course.getOwnerUserId(), course.getVisibility().name(), course.getTitle());
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadiusMeters * c;
    }
}
