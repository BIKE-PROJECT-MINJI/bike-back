package com.bikeprojectminji.bikeback.course.controller;

import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseDetailResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListResponse;
import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseRequest;
import com.bikeprojectminji.bikeback.course.dto.UpdateCourseVisibilityRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import java.math.BigDecimal;
import com.bikeprojectminji.bikeback.course.service.CourseService;
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

    @GetMapping("/{courseId}")
    public ApiResponse<CourseDetailResponse> getCourseDetail(
            @PathVariable Long courseId,
            @RequestParam(required = false) String shareToken,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(courseService.getCourseDetail(courseId, jwt != null ? jwt.getSubject() : null, shareToken));
    }

    @GetMapping("/{courseId}/route-points")
    public ApiResponse<CourseRoutePointsResponse> getCourseRoutePoints(
            @PathVariable Long courseId,
            @RequestParam(required = false) String shareToken,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(courseService.getCourseRoutePoints(courseId, jwt != null ? jwt.getSubject() : null, shareToken));
    }

    @GetMapping("/search")
    public ApiResponse<CourseListResponse> searchCourses(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort
    ) {
        return ApiResponse.success(courseService.searchPublicCourses(q, sort));
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

    @PostMapping("/{courseId}/share")
    public ApiResponse<CourseShareResponse> getCourseShareInfo(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long courseId
    ) {
        return ApiResponse.success(courseService.getCourseShareInfo(jwt.getSubject(), courseId));
    }

    @GetMapping("/{courseId}/download")
    public ApiResponse<CourseDownloadResponse> downloadCourse(
            @PathVariable Long courseId,
            @RequestParam(required = false) String shareToken,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(courseService.downloadCourse(courseId, jwt != null ? jwt.getSubject() : null, shareToken));
    }
}
