package com.bikeprojectminji.bikeback.dto.profile;

public record ProfileMeResponse(
        Long userId,
        String email,
        String displayName,
        String profileImageUrl
) {
}
