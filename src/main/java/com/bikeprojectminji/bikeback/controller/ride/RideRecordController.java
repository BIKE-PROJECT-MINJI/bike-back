package com.bikeprojectminji.bikeback.controller.ride;

import com.bikeprojectminji.bikeback.dto.ride.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.dto.ride.RideRecordResponse;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.service.ride.RideRecordService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ride-records")
public class RideRecordController {

    private final RideRecordService rideRecordService;

    public RideRecordController(RideRecordService rideRecordService) {
        this.rideRecordService = rideRecordService;
    }

    @PostMapping
    public ApiResponse<RideRecordResponse> saveRideRecord(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateRideRecordRequest request
    ) {
        return ApiResponse.success(rideRecordService.saveRideRecord(jwt.getSubject(), request));
    }
}
