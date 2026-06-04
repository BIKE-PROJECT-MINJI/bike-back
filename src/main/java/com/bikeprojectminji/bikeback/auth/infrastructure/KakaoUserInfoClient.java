package com.bikeprojectminji.bikeback.auth.infrastructure;

import com.bikeprojectminji.bikeback.auth.service.KakaoAccountClient;
import com.bikeprojectminji.bikeback.auth.service.KakaoAccountProfile;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoUserInfoClient implements KakaoAccountClient {

    private final RestClient restClient;
    private final String userInfoUrl;

    public KakaoUserInfoClient(
            RestClient.Builder restClientBuilder,
            @Value("${auth.kakao.user-info-url:https://kapi.kakao.com/v2/user/me}") String userInfoUrl
    ) {
        this.restClient = restClientBuilder.build();
        this.userInfoUrl = userInfoUrl;
    }

    @Override
    public KakaoAccountProfile fetchProfile(String kakaoAccessToken) {
        try {
            KakaoUserInfoResponse response = restClient.get()
                    .uri(userInfoUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                    .retrieve()
                    .body(KakaoUserInfoResponse.class);

            if (response == null || response.id() == null) {
                throw new UnauthorizedException("카카오 로그인 정보가 올바르지 않습니다.");
            }

            return new KakaoAccountProfile(
                    String.valueOf(response.id()),
                    response.nickname(),
                    response.profileImageUrl(),
                    response.email()
            );
        } catch (UnauthorizedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UnauthorizedException("카카오 로그인 정보가 올바르지 않습니다.");
        }
    }

    record KakaoUserInfoResponse(
            Long id,
            KakaoAccount kakao_account,
            Properties properties
    ) {
        String nickname() {
            if (kakao_account != null && kakao_account.profile != null && kakao_account.profile.nickname != null) {
                return kakao_account.profile.nickname;
            }
            return properties == null ? null : properties.nickname;
        }

        String profileImageUrl() {
            if (kakao_account != null && kakao_account.profile != null && kakao_account.profile.profile_image_url != null) {
                return kakao_account.profile.profile_image_url;
            }
            return properties == null ? null : properties.profile_image;
        }

        String email() {
            return kakao_account == null ? null : kakao_account.email;
        }
    }

    record KakaoAccount(
            String email,
            KakaoProfile profile
    ) {
    }

    record KakaoProfile(
            String nickname,
            String profile_image_url
    ) {
    }

    record Properties(
            String nickname,
            String profile_image
    ) {
    }
}
