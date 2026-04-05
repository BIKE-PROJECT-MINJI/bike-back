package com.bikeprojectminji.bikeback.auth.dto;

public record LoginResponse(
        String tokenType,
        String accessToken,
        long expiresInSec,
        Long userId,
        String displayName
) {
}
