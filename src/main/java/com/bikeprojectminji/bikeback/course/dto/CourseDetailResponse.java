package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseDetailResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin,
        Long sourceRideRecordId,
        boolean sourceDetached,
        CourseDifficultyResponse difficulty
) {

    public CourseDetailResponse(Long id, String title, BigDecimal distanceKm, Integer estimatedDurationMin) {
        this(id, title, distanceKm, estimatedDurationMin, null, false, null);
    }

    public CourseDetailResponse(Long id, String title, BigDecimal distanceKm, Integer estimatedDurationMin, Long sourceRideRecordId) {
        this(id, title, distanceKm, estimatedDurationMin, sourceRideRecordId, false, null);
    }

    public CourseDetailResponse(
            Long id,
            String title,
            BigDecimal distanceKm,
            Integer estimatedDurationMin,
            Long sourceRideRecordId,
            boolean sourceDetached
    ) {
        this(id, title, distanceKm, estimatedDurationMin, sourceRideRecordId, sourceDetached, null);
    }
}
