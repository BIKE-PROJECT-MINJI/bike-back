package com.bikeprojectminji.bikeback.service.course;

import com.bikeprojectminji.bikeback.dto.course.CourseListItemResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseListResponse;
import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import com.bikeprojectminji.bikeback.repository.course.CourseRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CourseService {

    private static final int DEFAULT_LIMIT = 10;

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

    private int resolveLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return limit;
    }
}
