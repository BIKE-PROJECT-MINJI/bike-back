package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseDetailResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin,
        Long sourceRideRecordId
) {

    public CourseDetailResponse(Long id, String title, BigDecimal distanceKm, Integer estimatedDurationMin) {
        this(id, title, distanceKm, estimatedDurationMin, null);
    }
}
