package com.bikeprojectminji.bikeback.global.metrics;

import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
import com.bikeprojectminji.bikeback.global.logging.ObservabilityLoggingProperties;
import java.time.Duration;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class OperationMetricsAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationMetricsAspect.class);

    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final ObservabilityLoggingProperties loggingProperties;

    public OperationMetricsAspect(
            BikeMetricsRecorder bikeMetricsRecorder,
            ObservabilityLoggingProperties loggingProperties
    ) {
        this.bikeMetricsRecorder = bikeMetricsRecorder;
        this.loggingProperties = loggingProperties;
    }

    @Around("@annotation(measuredOperation)")
    public Object recordOperation(ProceedingJoinPoint joinPoint, MeasuredOperation measuredOperation) throws Throwable {
        long startedAtNanos = System.nanoTime();
        String outcome = "success";
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            outcome = "failure";
            throw throwable;
        } finally {
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAtNanos);
            String operation = measuredOperation.value();
            long durationMs = duration.toMillis();
            bikeMetricsRecorder.recordOperationDuration(operation, outcome, duration);
            if (loggingProperties.shouldLogOperation(outcome, durationMs)) {
                log.info(
                        "operation_duration request_id={} trace_id={} operation={} outcome={} duration_ms={}",
                        RequestLogContext.currentRequestId(),
                        RequestLogContext.currentTraceId(),
                        operation,
                        outcome,
                        durationMs
                );
            }
        }
    }
}
