package com.bikeprojectminji.bikeback.profile.dto;

public record ProfileMeResponse(
        Long userId,
        String email,
        String displayName,
        String profileImageUrl
) {
}
