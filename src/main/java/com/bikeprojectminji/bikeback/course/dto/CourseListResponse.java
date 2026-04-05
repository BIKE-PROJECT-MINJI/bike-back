package com.bikeprojectminji.bikeback.course.dto;

import java.util.List;

public record CourseListResponse(
        List<CourseListItemResponse> items,
        boolean hasNext,
        String nextCursor
) {
}
