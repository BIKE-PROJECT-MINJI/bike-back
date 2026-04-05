package com.bikeprojectminji.bikeback.auth.dto;

public record RegisterRequest(
        String email,
        String password,
        String displayName,
        String profileImageUrl,
        String legacyExternalId
) {
}
