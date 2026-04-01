package com.bikeprojectminji.bikeback.dto.course;

import java.util.List;

public record CourseRoutePointsResponse(
        Long courseId,
        List<CourseRoutePointResponse> points
) {
}
