package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.exception.RetryableServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RideSaveConcurrencyGate {

    public static final String ERROR_CODE = "RIDE_SAVE_BUSY";

    private static final Logger log = LoggerFactory.getLogger(RideSaveConcurrencyGate.class);

    private final RideSaveConcurrencyGateProperties properties;
    private final BikeMetricsRecorder metricsRecorder;
    private final Semaphore semaphore;

    public RideSaveConcurrencyGate(
            RideSaveConcurrencyGateProperties properties,
            BikeMetricsRecorder metricsRecorder
    ) {
        this.properties = properties;
        this.metricsRecorder = metricsRecorder;
        this.semaphore = new Semaphore(properties.getMaxConcurrency());
    }

    public <T> T execute(Supplier<T> supplier) {
        if (!properties.isEnabled()) {
            metricsRecorder.recordRideSaveConcurrencyGate("disabled");
            return supplier.get();
        }
        if (!semaphore.tryAcquire()) {
            metricsRecorder.recordRideSaveConcurrencyGate("rejected");
            log.warn(
                    "ride_save_busy max_concurrency={} retry_after_seconds={}",
                    properties.getMaxConcurrency(),
                    properties.getRetryAfterSeconds()
            );
            throw new RetryableServiceUnavailableException(
                    properties.getMessage(),
                    ERROR_CODE,
                    properties.getRetryAfterSeconds()
            );
        }
        try {
            metricsRecorder.recordRideSaveConcurrencyGate("accepted");
            return supplier.get();
        } finally {
            semaphore.release();
        }
    }
}
