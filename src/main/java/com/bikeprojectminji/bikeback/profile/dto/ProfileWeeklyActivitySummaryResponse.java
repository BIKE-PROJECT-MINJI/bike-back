package com.bikeprojectminji.bikeback.profile.dto;

import java.math.BigDecimal;

public record ProfileWeeklyActivitySummaryResponse(
        BigDecimal distanceKm,
        long rideCount,
        long durationMinutes,
        long savedCourseCount
) {
}
