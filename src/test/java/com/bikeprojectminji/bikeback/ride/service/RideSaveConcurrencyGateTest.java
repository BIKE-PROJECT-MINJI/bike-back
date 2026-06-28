package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.global.exception.RetryableServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RideSaveConcurrencyGateTest {

    @Mock
    private BikeMetricsRecorder metricsRecorder;

    @Test
    @DisplayName("저장 gate는 동시 실행 수를 넘으면 RIDE_SAVE_BUSY 503 예외로 빠르게 거절한다")
    void rejectWhenConcurrencyLimitIsReached() {
        RideSaveConcurrencyGateProperties properties = new RideSaveConcurrencyGateProperties();
        properties.setMaxConcurrency(1);
        properties.setRetryAfterSeconds(4);
        RideSaveConcurrencyGate gate = new RideSaveConcurrencyGate(properties, metricsRecorder);

        String result = gate.execute(() -> {
            assertThatThrownBy(() -> gate.execute(() -> "nested"))
                    .isInstanceOf(RetryableServiceUnavailableException.class)
                    .satisfies(exception -> {
                        RetryableServiceUnavailableException busy = (RetryableServiceUnavailableException) exception;
                        assertThat(busy.getErrorCode()).isEqualTo("RIDE_SAVE_BUSY");
                        assertThat(busy.getRetryAfterSeconds()).isEqualTo(4);
                    });
            return "saved";
        });

        assertThat(result).isEqualTo("saved");
        assertThat(gate.execute(() -> "after-release")).isEqualTo("after-release");
        verify(metricsRecorder).recordRideSaveConcurrencyGate("rejected");
    }

    @Test
    @DisplayName("저장 gate가 비활성화되면 supplier를 제한 없이 실행한다")
    void executeWithoutLimitWhenDisabled() {
        RideSaveConcurrencyGateProperties properties = new RideSaveConcurrencyGateProperties();
        properties.setEnabled(false);
        RideSaveConcurrencyGate gate = new RideSaveConcurrencyGate(properties, metricsRecorder);

        assertThat(gate.execute(() -> "saved")).isEqualTo("saved");

        verify(metricsRecorder).recordRideSaveConcurrencyGate("disabled");
    }
}
