package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record FeaturedCourseItemResponse(
        Long id,
        String title,
        BigDecimal distanceKm,
        Integer estimatedDurationMin,
        Integer distanceFromUserM,
        Integer featuredRank
) {
}
