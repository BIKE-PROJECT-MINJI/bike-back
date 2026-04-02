package com.bikeprojectminji.bikeback.controller.course;

import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseWriteResponse;
import com.bikeprojectminji.bikeback.dto.course.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.dto.course.CourseDetailResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseListResponse;
import com.bikeprojectminji.bikeback.dto.course.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.dto.course.UpdateCourseRequest;
import com.bikeprojectminji.bikeback.dto.course.UpdateCourseVisibilityRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import java.math.BigDecimal;
import com.bikeprojectminji.bikeback.service.course.CourseService;
import com.bikeprojectminji.bikeback.service.ridepolicy.RidePolicyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/courses")
public class CourseController {

    private final CourseService courseService;
    private final RidePolicyService ridePolicyService;

    public CourseController(CourseService courseService, RidePolicyService ridePolicyService) {
        this.courseService = courseService;
        this.ridePolicyService = ridePolicyService;
    }

    @GetMapping
    public ApiResponse<CourseListResponse> getCourses(
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.success(courseService.getCourses(cursor, limit));
    }

    @GetMapping("/{courseId}")
    public ApiResponse<CourseDetailResponse> getCourseDetail(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(courseService.getCourseDetail(courseId, jwt != null ? jwt.getSubject() : null));
    }

    @GetMapping("/{courseId}/route-points")
    public ApiResponse<CourseRoutePointsResponse> getCourseRoutePoints(
            @PathVariable Long courseId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(courseService.getCourseRoutePoints(courseId, jwt != null ? jwt.getSubject() : null));
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

    @PostMapping("/{courseId}/ride-policy/evaluate")
    public ApiResponse<RidePolicyEvaluationResponse> evaluateRidePolicy(
            @PathVariable Long courseId,
            @RequestBody RidePolicyEvaluationRequest request
    ) {
        return ApiResponse.success(ridePolicyService.evaluate(courseId, request));
    }

    @PostMapping
    public ApiResponse<CourseWriteResponse> createCourseFromRideRecord(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateCourseFromRideRecordRequest request
    ) {
        return ApiResponse.success(courseService.createCourseFromRideRecord(jwt.getSubject(), request));
    }

    @PutMapping("/{courseId}")
    public ApiResponse<CourseWriteResponse> updateCourse(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long courseId,
            @RequestBody UpdateCourseRequest request
    ) {
        return ApiResponse.success(courseService.updateCourse(jwt.getSubject(), courseId, request));
    }

    @PatchMapping("/{courseId}/visibility")
    public ApiResponse<CourseWriteResponse> updateCourseVisibility(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long courseId,
            @RequestBody UpdateCourseVisibilityRequest request
    ) {
        return ApiResponse.success(courseService.updateCourseVisibility(jwt.getSubject(), courseId, request));
    }
}
