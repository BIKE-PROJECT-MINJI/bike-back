package com.bikeprojectminji.bikeback.dto.course;

import java.math.BigDecimal;

public record CourseRoutePointResponse(
        Integer pointOrder,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
