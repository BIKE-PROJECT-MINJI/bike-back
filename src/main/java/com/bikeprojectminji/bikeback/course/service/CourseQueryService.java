package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CourseDetailResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListItemResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseItemResponse;
import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseListRow;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.FeaturedCourseDistanceCandidate;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CourseQueryService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int FEATURED_LIMIT = 3;
    private static final int MAX_LIMIT = 50;
    private static final Logger log = LoggerFactory.getLogger(CourseQueryService.class);

    private final CourseRepository courseRepository;
    private final AuthService authService;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final CourseRouteSnapshotService courseRouteSnapshotService;
    private final CourseAccessPolicy courseAccessPolicy = new CourseAccessPolicy();

    public CourseQueryService(
            CourseRepository courseRepository,
            AuthService authService,
            BikeMetricsRecorder bikeMetricsRecorder,
            CourseRouteSnapshotService courseRouteSnapshotService
    ) {
        this.courseRepository = courseRepository;
        this.authService = authService;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
        this.courseRouteSnapshotService = courseRouteSnapshotService;
    }

    public CourseListResponse getCourses(Long cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        List<CourseListRow> queriedCourses = courseRepository.findPublicListPageAfter(cursor, pageSize + 1);

        boolean hasNext = queriedCourses.size() > pageSize;
        List<CourseListRow> pageCourses = hasNext ? queriedCourses.subList(0, pageSize) : queriedCourses;
        List<CourseListItemResponse> items = pageCourses.stream()
                .map(row -> new CourseListItemResponse(
                        row.id(),
                        row.title(),
                        row.distanceKm(),
                        row.estimatedDurationMin()
                ))
                .toList();
        String nextCursor = hasNext && !pageCourses.isEmpty()
                ? String.valueOf(pageCourses.get(pageCourses.size() - 1).id())
                : null;

        return new CourseListResponse(items, hasNext, nextCursor);
    }

    public CourseDetailResponse getCourseDetail(Long courseId, String subject, String shareToken) {
        CourseEntity course = findReadableCourse(courseId, subject, shareToken);
        return new CourseDetailResponse(
                course.getId(),
                course.getTitle(),
                course.getDistanceKm(),
                course.getEstimatedDurationMin(),
                course.getSourceRideRecordId(),
                course.isSourceDetached()
        );
    }

    public CourseRoutePointsResponse getCourseRoutePoints(Long courseId, String subject, String shareToken) {
        CourseEntity course = findReadableCourse(courseId, subject, shareToken);
        List<CourseRoutePointResponse> points = courseRouteSnapshotService.get(course.getId(), "route_points").responsePoints();
        return new CourseRoutePointsResponse(course.getId(), points);
    }

    public CourseListResponse searchPublicCourses(String query, String sort) {
        validateSearchSort(sort);
        List<CourseEntity> courses = isBlank(query)
                ? courseRepository.findTop20ByVisibilityAndReportHiddenFalseOrderByIdDesc(CourseVisibility.PUBLIC)
                : courseRepository.findTop20ByVisibilityAndReportHiddenFalseAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility.PUBLIC, query.trim());
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

    public FeaturedCourseResponse getFeaturedCourses(BigDecimal lat, BigDecimal lon) {
        boolean distanceMode = lat != null && lon != null;
        if (distanceMode) {
            List<FeaturedCourseDistanceCandidate> postgisCandidates = findFeaturedCoursesNear(lat, lon);
            if (!postgisCandidates.isEmpty()) {
                return new FeaturedCourseResponse("distance", postgisCandidates.stream()
                        .map(this::toFeaturedResponse)
                        .toList());
            }
        }

        List<CourseEntity> featuredCourses = courseRepository.findFeaturedCourses();
        if (featuredCourses.isEmpty()) {
            bikeMetricsRecorder.recordFeaturedCoursesFallback("no_curated_courses");
            log.info("featured_courses_fallback request_id={} reason=no_curated_courses", RequestLogContext.currentRequestId());
            return new FeaturedCourseResponse("fallback", List.of());
        }

        if (!distanceMode) {
            bikeMetricsRecorder.recordFeaturedCoursesFallback("missing_location_parameters");
            log.info("featured_courses_fallback request_id={} reason=missing_location_parameters", RequestLogContext.currentRequestId());
        }

        List<FeaturedCourseItemResponse> items = (distanceMode ? featuredCourses.stream()
                .map(course -> toFeaturedResponse(course, lat, lon))
                .sorted(Comparator
                        .comparing(FeaturedCourseItemResponse::distanceFromUserM, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FeaturedCourseItemResponse::featuredRank, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(FeaturedCourseItemResponse::id))
                .limit(FEATURED_LIMIT)
                .toList() : featuredCourses.stream()
                .map(course -> toFeaturedResponse(course, null, null))
                .limit(FEATURED_LIMIT)
                .toList());

        return new FeaturedCourseResponse(distanceMode ? "distance" : "fallback", items);
    }

    public CourseDownloadResponse downloadCourse(Long courseId, String subject, String shareToken) {
        CourseEntity course = findReadableCourse(courseId, subject, shareToken);
        List<CourseRoutePointResponse> routePoints = courseRouteSnapshotService.get(course.getId(), "course_download").responsePoints();
        return new CourseDownloadResponse(course.getId(), course.getTitle(), course.getVisibility().name(), routePoints);
    }

    private List<FeaturedCourseDistanceCandidate> findFeaturedCoursesNear(BigDecimal lat, BigDecimal lon) {
        try {
            List<FeaturedCourseDistanceCandidate> candidates = courseRepository.findFeaturedCoursesNear(lat, lon, FEATURED_LIMIT);
            if (candidates == null || candidates.isEmpty()) {
                bikeMetricsRecorder.recordFeaturedCoursesFallback("postgis_distance_query_empty");
                return List.of();
            }
            return candidates;
        } catch (RuntimeException exception) {
            bikeMetricsRecorder.recordFeaturedCoursesFallback("postgis_distance_query_failed");
            log.warn("featured_courses_postgis_fallback request_id={} reason=postgis_distance_query_failed", RequestLogContext.currentRequestId(), exception);
            return List.of();
        }
    }

    private CourseEntity findReadableCourse(Long courseId, String subject, String shareToken) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        UserEntity user = isBlank(subject) ? null : authService.findUserBySubject(subject);
        courseAccessPolicy.assertNotReportHidden(course, user);
        courseAccessPolicy.assertReadable(course, user, shareToken);
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

    private int resolveLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
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

    private FeaturedCourseItemResponse toFeaturedResponse(FeaturedCourseDistanceCandidate candidate) {
        CourseEntity course = candidate.course();
        return new FeaturedCourseItemResponse(
                course.getId(),
                course.getTitle(),
                course.getDistanceKm(),
                course.getEstimatedDurationMin(),
                candidate.distanceFromUserM(),
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
