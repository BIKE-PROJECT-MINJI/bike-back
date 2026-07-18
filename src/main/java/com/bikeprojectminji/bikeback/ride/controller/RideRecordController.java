package com.bikeprojectminji.bikeback.ride.controller;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordFinalizationStatusResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordListResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordReceiptRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordTraceRequest;
import com.bikeprojectminji.bikeback.ride.service.RideRecordDeletionService;
import com.bikeprojectminji.bikeback.ride.service.RideRecordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final RideRecordDeletionService rideRecordDeletionService;

    public RideRecordController(RideRecordService rideRecordService, RideRecordDeletionService rideRecordDeletionService) {
        this.rideRecordService = rideRecordService;
        this.rideRecordDeletionService = rideRecordDeletionService;
    }

    @PostMapping
    public ApiResponse<RideRecordResponse> saveRideRecord(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateRideRecordRequest request
    ) {
        return ApiResponse.success(rideRecordService.saveRideRecord(jwt.getSubject(), request));
    }

    @PostMapping("/summary")
    public ApiResponse<RideRecordResponse> saveRideRecordSummary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateRideRecordSummaryRequest request
    ) {
        return ApiResponse.success(rideRecordService.saveRideRecordSummary(jwt.getSubject(), request));
    }

    @PostMapping("/{rideRecordId}/trace")
    public ApiResponse<RideRecordResponse> saveRideRecordTrace(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long rideRecordId,
            @RequestBody RideRecordTraceRequest request
    ) {
        return ApiResponse.success(rideRecordService.saveRideRecordTrace(jwt.getSubject(), rideRecordId, request));
    }

    @GetMapping
    public ApiResponse<RideRecordListResponse> listRideRecords(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(rideRecordService.listRideRecords(jwt.getSubject()));
    }

    @GetMapping("/{rideRecordId}")
    public ApiResponse<RideRecordFinalizationStatusResponse> getRideRecordStatus(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long rideRecordId
    ) {
        return ApiResponse.success(rideRecordService.getRideRecordStatus(jwt.getSubject(), rideRecordId));
    }

    @PostMapping("/receipt")
    public ApiResponse<RideRecordFinalizationStatusResponse> getRideRecordStatusByClientRideId(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody RideRecordReceiptRequest request
    ) {
        if (request == null) {
            throw new BadRequestException("요청 본문이 필요합니다.");
        }
        return ApiResponse.success(
                rideRecordService.getRideRecordStatusByClientRideId(jwt.getSubject(), request.clientRideId())
        );
    }

    @PostMapping("/{rideRecordId}/regenerate")
    public ApiResponse<RideRecordFinalizationStatusResponse> regenerateRideRecord(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long rideRecordId
    ) {
        return ApiResponse.success(rideRecordService.regenerateRideRecord(jwt.getSubject(), rideRecordId));
    }

    @DeleteMapping("/{rideRecordId}")
    public ResponseEntity<Void> deleteRideRecord(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long rideRecordId
    ) {
        rideRecordDeletionService.deleteRideRecord(jwt.getSubject(), rideRecordId);
        return ResponseEntity.noContent().build();
    }
}
