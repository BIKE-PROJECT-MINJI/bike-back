package com.bikeprojectminji.bikeback.ride.repository;

public record RideRecordActivityAggregate(
        Long overallRideCount,
        Long overallDistanceM,
        Long overallDurationSec,
        Long weeklyRideCount,
        Long weeklyDistanceM,
        Long weeklyDurationSec
) {
}
