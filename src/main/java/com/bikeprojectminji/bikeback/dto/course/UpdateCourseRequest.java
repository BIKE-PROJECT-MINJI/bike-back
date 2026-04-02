package com.bikeprojectminji.bikeback.dto.course;

import java.util.List;

public record UpdateCourseRequest(
        String name,
        String description,
        String visibility,
        List<CourseRoutePointRequest> routePoints
) {
}
