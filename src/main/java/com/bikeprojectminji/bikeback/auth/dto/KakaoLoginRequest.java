package com.bikeprojectminji.bikeback.auth.dto;

public record KakaoLoginRequest(
        String kakaoAccessToken,
        String privacyPolicyVersion,
        String termsVersion,
        String locationTermsVersion,
        String birthDate
) {

    public KakaoLoginRequest(
            String kakaoAccessToken,
            String privacyPolicyVersion,
            String termsVersion,
            String locationTermsVersion
    ) {
        this(kakaoAccessToken, privacyPolicyVersion, termsVersion, locationTermsVersion, "2000-01-01");
    }
}
