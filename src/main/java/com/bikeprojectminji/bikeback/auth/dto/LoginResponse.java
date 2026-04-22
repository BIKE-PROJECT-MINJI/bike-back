package com.bikeprojectminji.bikeback.auth.dto;

public record LoginResponse(
        String tokenType,
        String accessToken,
        String refreshToken,
        long accessExpiresInSec,
        long refreshExpiresInSec,
        Long userId,
        String displayName
) {
}
