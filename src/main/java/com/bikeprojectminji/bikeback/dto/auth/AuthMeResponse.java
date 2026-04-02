package com.bikeprojectminji.bikeback.dto.auth;

public record AuthMeResponse(
        Long userId,
        String email,
        String displayName,
        boolean authenticated,
        String role
) {
}
