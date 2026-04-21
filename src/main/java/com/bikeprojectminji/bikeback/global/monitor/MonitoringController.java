package com.bikeprojectminji.bikeback.global.monitor;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/health/monitor")
    public ApiResponse<MonitoringStatusResponse> getMonitoringStatus() {
        return ApiResponse.success(monitoringService.getStatus());
    }
}
