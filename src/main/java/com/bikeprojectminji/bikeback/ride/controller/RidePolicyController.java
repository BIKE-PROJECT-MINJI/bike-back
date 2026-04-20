package com.bikeprojectminji.bikeback.ride.controller;

import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.ride.policy.service.RidePolicyService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RidePolicyController {

    // 외부 계약 path는 course 아래를 유지하지만,
    // 실제 ownership은 ride.policy 도메인으로 옮겨 course와 책임을 분리한다.

    private final RidePolicyService ridePolicyService;

    public RidePolicyController(RidePolicyService ridePolicyService) {
        this.ridePolicyService = ridePolicyService;
    }

    @PostMapping("/api/v1/courses/{courseId}/ride-policy/evaluate")
    public ApiResponse<RidePolicyEvaluationResponse> evaluateRidePolicy(
            @PathVariable Long courseId,
            @RequestBody RidePolicyEvaluationRequest request
    ) {
        // 외부 API는 기존 course path를 그대로 유지해 앱 계약을 깨지 않고,
        // 내부 계산 책임만 ride.policy 서비스로 위임한다.
        return ApiResponse.success(ridePolicyService.evaluate(courseId, request));
    }
}
