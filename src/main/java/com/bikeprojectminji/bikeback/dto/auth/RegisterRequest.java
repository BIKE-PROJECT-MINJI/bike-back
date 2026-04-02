package com.bikeprojectminji.bikeback.dto.auth;

public record RegisterRequest(
        String email,
        String password,
        String displayName,
        String profileImageUrl
) {
}
