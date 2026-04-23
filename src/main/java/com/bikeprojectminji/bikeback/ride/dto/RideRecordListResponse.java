package com.bikeprojectminji.bikeback.ride.dto;

import java.util.List;

public record RideRecordListResponse(
        List<RideRecordListItemResponse> items
) {
}
