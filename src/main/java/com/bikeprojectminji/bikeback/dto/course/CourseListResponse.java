package com.bikeprojectminji.bikeback.dto.course;

import java.util.List;

public record CourseListResponse(
        List<CourseListItemResponse> items,
        boolean hasNext,
        String nextCursor
) {
}
