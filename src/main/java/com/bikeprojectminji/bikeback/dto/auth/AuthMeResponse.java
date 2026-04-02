package com.bikeprojectminji.bikeback.dto.auth;

public record AuthMeResponse(
        Long userId,
        String displayName,
        boolean authenticated,
        String role
) {
}
