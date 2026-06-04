package com.bikeprojectminji.bikeback.ride.policy.dto;

public record RidePolicyProgressResponse(
        Integer distanceAlongRouteM,
        Integer remainingDistanceM,
        Integer progressPercent,
        Integer nearestSegmentIndex
) {
}
