package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseListItemResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin
) {
}
