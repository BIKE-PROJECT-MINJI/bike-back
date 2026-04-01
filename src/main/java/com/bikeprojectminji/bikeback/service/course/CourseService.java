package com.bikeprojectminji.bikeback.service.course;

import com.bikeprojectminji.bikeback.dto.course.CourseListItemResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseListResponse;
import com.bikeprojectminji.bikeback.dto.course.FeaturedCourseItemResponse;
import com.bikeprojectminji.bikeback.dto.course.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import java.math.RoundingMode;
import com.bikeprojectminji.bikeback.repository.course.CourseRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CourseService {

    private static final int DEFAULT_LIMIT = 10;
    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private final CourseRepository courseRepository;

    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    public CourseListResponse getCourses(Long cursor, Integer limit) {
        int pageSize = resolveLimit(limit);
        List<CourseEntity> queriedCourses = courseRepository.findPageAfter(cursor, pageSize + 1);

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

    public FeaturedCourseResponse getFeaturedCourses(BigDecimal lat, BigDecimal lon) {
        List<CourseEntity> featuredCourses = courseRepository.findFeaturedCourses();
        if (featuredCourses.isEmpty()) {
            log.info("featured courses fallback: no curated courses available");
            return new FeaturedCourseResponse("fallback", List.of());
        }

        boolean distanceMode = lat != null && lon != null;
        if (!distanceMode) {
            log.info("featured courses fallback: request without location parameters");
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
