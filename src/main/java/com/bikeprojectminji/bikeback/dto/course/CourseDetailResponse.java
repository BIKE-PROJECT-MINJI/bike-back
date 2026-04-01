package com.bikeprojectminji.bikeback.dto.course;

import java.math.BigDecimal;

public record CourseDetailResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin
) {
}
