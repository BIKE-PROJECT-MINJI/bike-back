package com.bikeprojectminji.bikeback.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.bikeprojectminji.bikeback.global.logging.ObservabilityLoggingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OperationMetricsAspectTest {

    @Test
    @DisplayName("성공한 내부 operation은 success outcome timer로 기록된다")
    void recordOperationRecordsSuccessDuration() throws Throwable {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OperationMetricsAspect aspect = new OperationMetricsAspect(
                new BikeMetricsRecorder(meterRegistry),
                new ObservabilityLoggingProperties()
        );
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        given(joinPoint.proceed()).willReturn("ok");

        Object result = aspect.recordOperation(joinPoint, measuredOperation("successOperation"));

        assertThat(result).isEqualTo("ok");
        assertThat(meterRegistry.get("bike_operation_duration")
                .tag("operation", "test.operation")
                .tag("outcome", "success")
                .timer()
                .count()).isEqualTo(1);
    }

    @Test
    @DisplayName("실패한 내부 operation은 예외를 보존하고 failure outcome timer로 기록된다")
    void recordOperationRecordsFailureDuration() throws Throwable {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OperationMetricsAspect aspect = new OperationMetricsAspect(
                new BikeMetricsRecorder(meterRegistry),
                new ObservabilityLoggingProperties()
        );
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        IllegalStateException failure = new IllegalStateException("boom");
        given(joinPoint.proceed()).willThrow(failure);

        assertThatThrownBy(() -> aspect.recordOperation(joinPoint, measuredOperation("failureOperation")))
                .isSameAs(failure);

        assertThat(meterRegistry.get("bike_operation_duration")
                .tag("operation", "test.operation")
                .tag("outcome", "failure")
                .timer()
                .count()).isEqualTo(1);
    }

    private MeasuredOperation measuredOperation(String methodName) throws NoSuchMethodException {
        Method method = TestOperations.class.getDeclaredMethod(methodName);
        return method.getAnnotation(MeasuredOperation.class);
    }

    private static class TestOperations {

        @MeasuredOperation("test.operation")
        private void successOperation() {
        }

        @MeasuredOperation("test.operation")
        private void failureOperation() {
        }
    }
}
