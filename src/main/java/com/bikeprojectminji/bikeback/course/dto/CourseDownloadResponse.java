package com.bikeprojectminji.bikeback.course.dto;

import java.util.List;

public record CourseDownloadResponse(
        Long courseId,
        String name,
        String visibility,
        List<CourseRoutePointResponse> routePoints
) {
}
