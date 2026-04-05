package com.bikeprojectminji.bikeback.course.dto;

import java.util.List;

public record UpdateCourseRequest(
        String name,
        String description,
        String visibility,
        List<CourseRoutePointRequest> routePoints
) {
}
