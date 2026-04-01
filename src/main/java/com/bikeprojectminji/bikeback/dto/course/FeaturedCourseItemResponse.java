package com.bikeprojectminji.bikeback.dto.course;

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
