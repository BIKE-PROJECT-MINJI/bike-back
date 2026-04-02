package com.bikeprojectminji.bikeback.dto.auth;

public record LoginResponse(
        String tokenType,
        String accessToken,
        long expiresInSec,
        Long userId,
        String displayName
) {
}
