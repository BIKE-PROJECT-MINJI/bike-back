package com.bikeprojectminji.bikeback.auth.dto;

public record AuthMeResponse(
        Long userId,
        String email,
        String displayName,
        boolean authenticated,
        String role
) {
}
