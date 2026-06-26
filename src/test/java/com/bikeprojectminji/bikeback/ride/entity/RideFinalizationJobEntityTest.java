package com.bikeprojectminji.bikeback.ride.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RideFinalizationJobEntityTest {

    @Test
    @DisplayName("RUNNING lease 소유자는 worker id와 attempt count가 모두 일치해야 한다")
    void runningLeaseRequiresWorkerIdAndAttemptCount() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-26T08:00:00Z");
        RideFinalizationJobEntity job = new RideFinalizationJobEntity(10L, now);

        job.markRunning("worker-a", now, now.plusMinutes(1));

        assertThat(job.isOwnedRunningLease("worker-a", 1)).isTrue();
        assertThat(job.isOwnedRunningLease("worker-b", 1)).isFalse();
        assertThat(job.isOwnedRunningLease("worker-a", 2)).isFalse();

        job.enqueue(now.plusSeconds(10));

        assertThat(job.isOwnedRunningLease("worker-a", 1)).isFalse();
    }
}
