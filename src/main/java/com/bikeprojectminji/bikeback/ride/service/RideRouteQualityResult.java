package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRouteQualityStatus;
import java.util.List;

public record RideRouteQualityResult(
        List<RideRecordPointRequest> selectedSegment,
        int distanceM,
        RideRouteQualityStatus status,
        List<String> reasons
) {
}
