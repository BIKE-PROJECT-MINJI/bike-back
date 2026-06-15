package com.bikeprojectminji.bikeback.course.repository;

import java.math.BigDecimal;

public record CourseListRow(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin
) {
}
