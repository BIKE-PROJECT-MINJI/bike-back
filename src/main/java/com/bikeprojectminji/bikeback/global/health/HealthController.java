package com.bikeprojectminji.bikeback.global.health;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

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
}
