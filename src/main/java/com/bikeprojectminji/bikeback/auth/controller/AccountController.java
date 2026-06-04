package com.bikeprojectminji.bikeback.auth.controller;

import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AuthService authService;

    public AccountController(AuthService authService) {
        this.authService = authService;
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> deleteMyAccount(@AuthenticationPrincipal Jwt jwt) {
        authService.deleteCurrentUser(jwt.getSubject());
        return ApiResponse.success(null);
    }
}
