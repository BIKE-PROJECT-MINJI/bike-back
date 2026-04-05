package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseDetailResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin
) {
}
