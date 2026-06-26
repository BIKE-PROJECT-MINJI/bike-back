package com.bikeprojectminji.bikeback.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserRole;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthTokenServiceTest {

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private JwtEncoder jwtEncoder;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("토큰 응답 발급은 refresh token 해시를 사용자 subject 기준으로 저장한다")
    void issueLoginResponseStoresRefreshTokenHash() {
        AuthTokenService authTokenService = createAuthTokenService();
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "password-hash", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("access-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(900))
                        .build()
        ).willReturn(
                Jwt.withTokenValue("refresh-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );

        LoginResponse response = authTokenService.issueLoginResponse(user);

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.userId()).isEqualTo(1L);
        ArgumentCaptor<RefreshTokenSession> sessionCaptor = ArgumentCaptor.forClass(RefreshTokenSession.class);
        verify(refreshTokenStore).save(
                org.mockito.ArgumentMatchers.eq("1"),
                sessionCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(1209600L))
        );
        assertThat(sessionCaptor.getValue().subject()).isEqualTo("1");
        assertThat(sessionCaptor.getValue().tokenHash()).isEqualTo(tokenHash("refresh-token"));
    }

    @Test
    @DisplayName("access token은 사용자 role을 roles claim으로 발급한다")
    void issueLoginResponseAddsRolesClaimToAccessToken() {
        AuthTokenService authTokenService = createAuthTokenService();
        UserEntity user = new UserEntity(null, "ops@example.com", "password-hash", "ops-rider", null);
        ReflectionTestUtils.setField(user, "id", 9L);
        user.grantRole(UserRole.OPS_ADMIN);
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("access-token")
                        .header("alg", "HS256")
                        .subject("9")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(900))
                        .build()
        ).willReturn(
                Jwt.withTokenValue("refresh-token")
                        .header("alg", "HS256")
                        .subject("9")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );

        authTokenService.issueLoginResponse(user);

        ArgumentCaptor<JwtEncoderParameters> tokenCaptor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder, org.mockito.Mockito.times(2)).encode(tokenCaptor.capture());
        JwtEncoderParameters accessTokenParameters = tokenCaptor.getAllValues().get(0);
        assertThat(accessTokenParameters.getClaims().getClaims().get("roles"))
                .asList()
                .containsExactly("USER", "OPS_ADMIN");
    }

    @Test
    @DisplayName("저장된 refresh token 해시가 요청 토큰과 다르면 인증 실패로 막는다")
    void validateStoredRefreshTokenRejectsMismatchedHash() {
        AuthTokenService authTokenService = createAuthTokenService();
        Jwt jwt = Jwt.withTokenValue("refresh-token")
                .header("alg", "HS256")
                .claim("tokenType", "refresh")
                .subject("1")
                .issuedAt(clock.instant())
                .expiresAt(clock.instant().plusSeconds(1209600))
                .build();
        given(refreshTokenStore.findBySubject("1"))
                .willReturn(Optional.of(new RefreshTokenSession("1", tokenHash("other-refresh-token"))));

        assertThatThrownBy(() -> authTokenService.validateStoredRefreshToken(jwt, "refresh-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    private AuthTokenService createAuthTokenService() {
        return new AuthTokenService(
                refreshTokenStore,
                jwtEncoder,
                clock,
                "bike-back-test",
                900L,
                1209600L
        );
    }

    private String tokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 계산에 실패했습니다.", exception);
        }
    }
}
