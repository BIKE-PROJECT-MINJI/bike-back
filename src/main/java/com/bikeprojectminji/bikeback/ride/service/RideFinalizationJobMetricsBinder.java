package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.ride.entity.RideFinalizationJobStatus;
import com.bikeprojectminji.bikeback.ride.repository.RideFinalizationJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class RideFinalizationJobMetricsBinder {

    public RideFinalizationJobMetricsBinder(
            MeterRegistry meterRegistry,
            RideFinalizationJobRepository rideFinalizationJobRepository
    ) {
        for (RideFinalizationJobStatus status : RideFinalizationJobStatus.values()) {
            Gauge.builder(
                            "bike_ride_finalization_job_count",
                            rideFinalizationJobRepository,
                            repository -> repository.countByStatus(status)
                    )
                    .tag("status", status.name().toLowerCase())
                    .register(meterRegistry);
        }
    }
}
