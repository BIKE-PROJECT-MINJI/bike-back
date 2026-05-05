package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.ride.policy.service.RouteProjectionIndex;
import java.util.List;

public record CourseRouteSnapshot(
        Long courseId,
        List<CourseRoutePointEntity> routePoints,
        List<CourseRoutePointResponse> responsePoints,
        RouteProjectionIndex routeProjectionIndex
) {
}
