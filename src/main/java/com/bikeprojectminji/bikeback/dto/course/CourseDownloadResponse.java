package com.bikeprojectminji.bikeback.dto.course;

import java.util.List;

public record CourseDownloadResponse(
        Long courseId,
        String name,
        String visibility,
        List<CourseRoutePointResponse> routePoints
) {
}
