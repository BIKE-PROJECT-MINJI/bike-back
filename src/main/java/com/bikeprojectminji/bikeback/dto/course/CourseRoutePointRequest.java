package com.bikeprojectminji.bikeback.dto.course;

import java.math.BigDecimal;

public record CourseRoutePointRequest(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
