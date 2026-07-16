package com.bikeprojectminji.bikeback.global.health;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.global.monitor.MonitoringService;
import com.bikeprojectminji.bikeback.global.monitor.MonitoringStatusResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final MonitoringService monitoringService;

    public HealthController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * Railway healthcheck와 운영 smoke가 동일한 기준으로 앱 생존 여부를 확인하도록 최소 health 응답을 제공한다.
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of(
                "status", "ok",
                "service", "bike-back"
        ));
    }

    @GetMapping("/ready")
    public ResponseEntity<ApiResponse<ReadinessStatusResponse>> ready() {
        MonitoringStatusResponse status = monitoringService.getStatus();
        if ("ok".equals(status.status())) {
            return ResponseEntity.ok(ApiResponse.success(new ReadinessStatusResponse("ready", "bike-back")));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(
                        503,
                        "서비스가 아직 요청을 받을 준비가 되지 않았습니다.",
                        new ReadinessStatusResponse("not_ready", "bike-back")
                ));
    }
}
