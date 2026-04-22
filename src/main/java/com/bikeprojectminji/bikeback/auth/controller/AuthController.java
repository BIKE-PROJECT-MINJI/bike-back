package com.bikeprojectminji.bikeback.auth.controller;

import com.bikeprojectminji.bikeback.auth.dto.AuthMeResponse;
import com.bikeprojectminji.bikeback.auth.dto.LoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.dto.RefreshTokenRequest;
import com.bikeprojectminji.bikeback.auth.dto.RegisterRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest request) {
        validateRegisterRequest(request);
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        validateLoginRequest(request);
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody RefreshTokenRequest request) {
        validateRefreshRequest(request);
        return ApiResponse.success(authService.refresh(request));
    }

    @GetMapping("/me")
    public ApiResponse<AuthMeResponse> getMyAuth(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(authService.getCurrentUser(jwt.getSubject()));
    }

    private void validateRegisterRequest(RegisterRequest request) {
        if (request == null) {
            throw new BadRequestException("회원가입 요청 본문이 필요합니다.");
        }
        if (isBlank(request.email())) {
            throw new BadRequestException("email은 비어 있을 수 없습니다.");
        }
        if (isBlank(request.displayName())) {
            throw new BadRequestException("displayName은 비어 있을 수 없습니다.");
        }
        if (isBlank(request.password())) {
            throw new BadRequestException("password는 비어 있을 수 없습니다.");
        }
    }

    private void validateLoginRequest(LoginRequest request) {
        if (request == null) {
            throw new BadRequestException("로그인 요청 본문이 필요합니다.");
        }
        if (isBlank(request.email())) {
            throw new BadRequestException("email은 비어 있을 수 없습니다.");
        }
        if (isBlank(request.password())) {
            throw new BadRequestException("password는 비어 있을 수 없습니다.");
        }
    }

    private void validateRefreshRequest(RefreshTokenRequest request) {
        if (request == null) {
            throw new BadRequestException("리프레시 요청 본문이 필요합니다.");
        }
        if (isBlank(request.refreshToken())) {
            throw new BadRequestException("refreshToken은 비어 있을 수 없습니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
