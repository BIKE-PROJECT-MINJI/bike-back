package com.bikeprojectminji.bikeback.controller.course;

import com.bikeprojectminji.bikeback.dto.course.CourseListResponse;
import com.bikeprojectminji.bikeback.dto.course.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import java.math.BigDecimal;
import com.bikeprojectminji.bikeback.service.course.CourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;

    public CourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public ApiResponse<CourseListResponse> getCourses(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(courseService.getCourses(cursor, limit));
    }

    @GetMapping("/featured")
    public ApiResponse<FeaturedCourseResponse> getFeaturedCourses(
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lon
    ) {
        validateFeaturedLocationQuery(lat, lon);
        return ApiResponse.success(courseService.getFeaturedCourses(lat, lon));
    }

    private void validateFeaturedLocationQuery(BigDecimal lat, BigDecimal lon) {
        if ((lat == null) != (lon == null)) {
            throw new BadRequestException("lat와 lon은 함께 전달되어야 합니다.");
        }
        if (lat != null && (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0)) {
            throw new BadRequestException("lat는 -90 이상 90 이하여야 합니다.");
        }
        if (lon != null && (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new BadRequestException("lon은 -180 이상 180 이하여야 합니다.");
        }
    }
}
