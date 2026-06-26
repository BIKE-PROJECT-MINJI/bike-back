package com.bikeprojectminji.bikeback.address.controller;

import com.bikeprojectminji.bikeback.address.dto.AddressSearchResponse;
import com.bikeprojectminji.bikeback.address.service.AddressSearchRateLimitService;
import com.bikeprojectminji.bikeback.address.service.AddressSearchService;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/addresses")
public class AddressSearchController {

    private final AddressSearchService addressSearchService;
    private final AddressSearchRateLimitService addressSearchRateLimitService;

    public AddressSearchController(AddressSearchService addressSearchService, AddressSearchRateLimitService addressSearchRateLimitService) {
        this.addressSearchService = addressSearchService;
        this.addressSearchRateLimitService = addressSearchRateLimitService;
    }

    @GetMapping("/search")
    public ApiResponse<AddressSearchResponse> search(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "5") Integer size,
            HttpServletRequest servletRequest
    ) {
        addressSearchRateLimitService.checkAllowed(servletRequest.getRemoteAddr());
        return ApiResponse.success(addressSearchService.search(query, page, size));
    }
}
