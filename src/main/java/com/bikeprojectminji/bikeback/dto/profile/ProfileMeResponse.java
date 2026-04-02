package com.bikeprojectminji.bikeback.dto.profile;

public record ProfileMeResponse(
        Long userId,
        String displayName,
        String profileImageUrl
) {
}
