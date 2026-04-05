package com.bikeprojectminji.bikeback.profile.dto;

public record UpdateProfileRequest(
        String displayName,
        String profileImageUrl
) {
}
