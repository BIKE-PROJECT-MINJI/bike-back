package com.bikeprojectminji.bikeback.dto.profile;

public record UpdateProfileRequest(
        String displayName,
        String profileImageUrl
) {
}
