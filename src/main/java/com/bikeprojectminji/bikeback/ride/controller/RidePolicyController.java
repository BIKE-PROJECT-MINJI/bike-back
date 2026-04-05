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

    private final RidePolicyService ridePolicyService;

    public RidePolicyController(RidePolicyService ridePolicyService) {
        this.ridePolicyService = ridePolicyService;
    }

    @PostMapping("/api/v1/courses/{courseId}/ride-policy/evaluate")
    public ApiResponse<RidePolicyEvaluationResponse> evaluateRidePolicy(
            @PathVariable Long courseId,
            @RequestBody RidePolicyEvaluationRequest request
    ) {
        return ApiResponse.success(ridePolicyService.evaluate(courseId, request));
    }
}
