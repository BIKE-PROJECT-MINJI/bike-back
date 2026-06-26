package com.bikeprojectminji.bikeback.global.metrics;

import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
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

    public OperationMetricsAspect(BikeMetricsRecorder bikeMetricsRecorder) {
        this.bikeMetricsRecorder = bikeMetricsRecorder;
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
            bikeMetricsRecorder.recordOperationDuration(operation, outcome, duration);
            log.info(
                    "operation_duration request_id={} trace_id={} operation={} outcome={} duration_ms={}",
                    RequestLogContext.currentRequestId(),
                    RequestLogContext.currentTraceId(),
                    operation,
                    outcome,
                    duration.toMillis()
            );
        }
    }
}
