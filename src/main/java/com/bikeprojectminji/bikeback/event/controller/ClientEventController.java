package com.bikeprojectminji.bikeback.event.controller;

import com.bikeprojectminji.bikeback.event.dto.ClientEventBatchResponse;
import com.bikeprojectminji.bikeback.event.dto.ClientEventResponse;
import com.bikeprojectminji.bikeback.event.dto.CreateClientEventBatchRequest;
import com.bikeprojectminji.bikeback.event.dto.CreateClientEventRequest;
import com.bikeprojectminji.bikeback.event.service.ClientEventService;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class ClientEventController {

    private final ClientEventService clientEventService;

    public ClientEventController(ClientEventService clientEventService) {
        this.clientEventService = clientEventService;
    }

    @PostMapping
    public ApiResponse<ClientEventResponse> saveEvent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateClientEventRequest request
    ) {
        return ApiResponse.success(clientEventService.saveEvent(jwt.getSubject(), request));
    }

    @PostMapping("/batch")
    public ApiResponse<ClientEventBatchResponse> saveEvents(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody CreateClientEventBatchRequest request
    ) {
        return ApiResponse.success(clientEventService.saveEvents(jwt.getSubject(), request));
    }
}
