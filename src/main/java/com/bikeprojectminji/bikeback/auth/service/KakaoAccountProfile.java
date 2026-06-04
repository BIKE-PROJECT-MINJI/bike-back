package com.bikeprojectminji.bikeback.auth.service;

public record KakaoAccountProfile(
        String providerUserId,
        String nickname,
        String profileImageUrl,
        String email
) {
}
