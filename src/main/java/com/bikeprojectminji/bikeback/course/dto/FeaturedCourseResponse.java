package com.bikeprojectminji.bikeback.course.dto;

import java.util.List;

public record FeaturedCourseResponse(
        String sortingMode,
        List<FeaturedCourseItemResponse> courses
) {
}
