package com.bikeprojectminji.bikeback.course.dto;

import java.util.List;

public record CourseRoutePointsResponse(
        Long courseId,
        List<CourseRoutePointResponse> points
) {
}
