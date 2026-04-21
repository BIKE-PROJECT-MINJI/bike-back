package com.bikeprojectminji.bikeback.global.monitor;

import java.time.OffsetDateTime;

public record MonitoringStatusResponse(
        String service,
        String status,
        OffsetDateTime checkedAt,
        DependencyStatusResponse database,
        DependencyStatusResponse redis
) {
}
