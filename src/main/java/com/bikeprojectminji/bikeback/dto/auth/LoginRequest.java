package com.bikeprojectminji.bikeback.dto.auth;

public record LoginRequest(
        String externalId,
        String displayName,
        String profileImageUrl
) {
}
