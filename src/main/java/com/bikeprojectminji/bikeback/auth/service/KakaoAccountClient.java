package com.bikeprojectminji.bikeback.auth.service;

public interface KakaoAccountClient {

    KakaoAccountProfile fetchProfile(String kakaoAccessToken);
}
