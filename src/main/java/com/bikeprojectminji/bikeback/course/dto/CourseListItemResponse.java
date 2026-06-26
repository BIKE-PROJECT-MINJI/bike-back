package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseListItemResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin,
        CourseDifficultyResponse difficulty
) {

    public CourseListItemResponse(Long id, String title, BigDecimal distanceKm, Integer estimatedDurationMin) {
        this(id, title, distanceKm, estimatedDurationMin, null);
    }
}
