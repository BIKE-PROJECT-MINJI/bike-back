package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseRoutePointResponse(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
