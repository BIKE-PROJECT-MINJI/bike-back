package com.bikeprojectminji.bikeback.dto.course;

import java.util.List;

public record FeaturedCourseResponse(
        String sortingMode,
        List<FeaturedCourseItemResponse> courses
) {
}
