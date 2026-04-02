package com.bikeprojectminji.bikeback.controller.auth;

import com.bikeprojectminji.bikeback.dto.auth.AuthMeResponse;
import com.bikeprojectminji.bikeback.dto.auth.LoginRequest;
import com.bikeprojectminji.bikeback.dto.auth.LoginResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import com.bikeprojectminji.bikeback.service.auth.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final boolean devLoginEnabled;
    private final String devLoginSecret;

    public AuthController(
            AuthService authService,
            @Value("${auth.login.dev-enabled:false}") boolean devLoginEnabled,
            @Value("${auth.login.dev-secret:}") String devLoginSecret
    ) {
        this.authService = authService;
        this.devLoginEnabled = devLoginEnabled;
        this.devLoginSecret = devLoginSecret;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @RequestBody LoginRequest request,
            @RequestHeader(value = "X-Dev-Login-Secret", required = false) String providedDevLoginSecret
    ) {
        validateLoginRequest(request, providedDevLoginSecret);
        return ApiResponse.success(authService.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<AuthMeResponse> getMyAuth(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(authService.getCurrentUser(jwt.getSubject()));
    }

    private void validateLoginRequest(LoginRequest request, String providedDevLoginSecret) {
        if (request == null) {
            throw new BadRequestException("로그인 요청 본문이 필요합니다.");
        }
        validateDevLoginAccess(providedDevLoginSecret);
        if (isBlank(request.externalId())) {
            throw new BadRequestException("externalId는 비어 있을 수 없습니다.");
        }
        if (isBlank(request.displayName())) {
            throw new BadRequestException("displayName은 비어 있을 수 없습니다.");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void validateDevLoginAccess(String providedDevLoginSecret) {
        if (!devLoginEnabled) {
            throw new ForbiddenException("현재 환경에서는 로그인 API를 사용할 수 없습니다.");
        }
        if (isBlank(devLoginSecret)) {
            throw new ForbiddenException("로그인 API 보안 설정이 올바르지 않습니다.");
        }
        if (!devLoginSecret.equals(providedDevLoginSecret)) {
            throw new ForbiddenException("로그인 API 접근이 허용되지 않았습니다.");
        }
    }
}
