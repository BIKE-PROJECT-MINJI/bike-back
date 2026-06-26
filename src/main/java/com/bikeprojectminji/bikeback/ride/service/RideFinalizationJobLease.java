package com.bikeprojectminji.bikeback.ride.service;

public record RideFinalizationJobLease(
        Long jobId,
        Long rideRecordId,
        int attemptCount
) {
}
