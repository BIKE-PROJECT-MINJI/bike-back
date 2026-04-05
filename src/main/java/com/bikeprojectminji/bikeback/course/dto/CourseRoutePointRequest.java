package com.bikeprojectminji.bikeback.course.dto;

import java.math.BigDecimal;

public record CourseRoutePointRequest(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
