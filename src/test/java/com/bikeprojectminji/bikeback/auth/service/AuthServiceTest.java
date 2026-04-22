package com.bikeprojectminji.bikeback.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.dto.LoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.dto.RefreshTokenRequest;
import com.bikeprojectminji.bikeback.auth.dto.RegisterRequest;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("회원가입은 사용자를 저장하고 JWT를 발급한다")
    void registerReturnsIssuedJwt() {
        AuthService authService = createAuthService();
        RegisterRequest request = new RegisterRequest("bikeoasis@example.com", "example-password", "bikeoasis", null, null);
        UserEntity savedUser = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(savedUser, "id", 1L);

        given(userRepository.existsByEmail("bikeoasis@example.com")).willReturn(false);
        given(userRepository.save(any(UserEntity.class))).willReturn(savedUser);
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("jwt-token")
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

        LoginResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.accessExpiresInSec()).isEqualTo(900L);
        assertThat(response.refreshExpiresInSec()).isEqualTo(1209600L);
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.displayName()).isEqualTo("bikeoasis");
    }

    @Test
    @DisplayName("신규 회원가입은 externalId를 비워 두지 않고 저장한다")
    void registerAssignsExternalIdForNewUser() {
        AuthService authService = createAuthService();
        RegisterRequest request = new RegisterRequest("fresh@example.com", "example-password", "fresh-rider", null, null);
        UserEntity savedUser = new UserEntity("generated-external-id", "fresh@example.com", passwordEncoder.encode("example-password"), "fresh-rider", null);
        ReflectionTestUtils.setField(savedUser, "id", 1L);

        given(userRepository.existsByEmail("fresh@example.com")).willReturn(false);
        given(userRepository.save(any(UserEntity.class))).willReturn(savedUser);
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("jwt-token")
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

        authService.register(request);

        ArgumentCaptor<UserEntity> userCaptor = forClass(UserEntity.class);
        org.mockito.Mockito.verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getExternalId()).isNotBlank();
    }

    @Test
    @DisplayName("로그인은 이메일과 비밀번호를 검증하고 JWT를 발급한다")
    void loginReturnsIssuedJwt() {
        AuthService authService = createAuthService();
        String encodedPassword = passwordEncoder.encode("example-password");
        LoginRequest request = new LoginRequest("bikeoasis@example.com", "example-password");
        UserEntity savedUser = new UserEntity(null, "bikeoasis@example.com", encodedPassword, "bikeoasis", null);
        ReflectionTestUtils.setField(savedUser, "id", 1L);

        given(userRepository.findByEmail("bikeoasis@example.com")).willReturn(Optional.of(savedUser));
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("jwt-token")
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

        LoginResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.accessExpiresInSec()).isEqualTo(900L);
        assertThat(response.refreshExpiresInSec()).isEqualTo(1209600L);
        assertThat(response.userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("리프레시는 유효한 refresh token이면 새 access/refresh 토큰을 발급한다")
    void refreshReturnsNewTokenPair() {
        AuthService authService = createAuthService();
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);

        given(jwtDecoder.decode("refresh-token")).willReturn(
                Jwt.withTokenValue("refresh-token")
                        .header("alg", "HS256")
                        .claim("tokenType", "refresh")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );
        given(refreshTokenStore.findBySubject("1"))
                .willReturn(Optional.of(new RefreshTokenSession("1", tokenHash("refresh-token"))));
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("new-access-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(900))
                        .build()
        ).willReturn(
                Jwt.withTokenValue("new-refresh-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );

        LoginResponse response = authService.refresh(new RefreshTokenRequest("refresh-token"));

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.displayName()).isEqualTo("bikeoasis");
    }

    @Test
    @DisplayName("리프레시는 성공 후 이전 refresh token 재사용을 401로 막는다")
    void refreshRejectsReusedTokenAfterRotation() {
        InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();
        AuthService authService = createAuthService(store);
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        store.save("1", new RefreshTokenSession("1", tokenHash("refresh-token")), Duration.ofDays(14));

        given(jwtDecoder.decode("refresh-token")).willReturn(
                Jwt.withTokenValue("refresh-token")
                        .header("alg", "HS256")
                        .claim("tokenType", "refresh")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );
        given(userRepository.findById(1L)).willReturn(Optional.of(user));
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("new-access-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(900))
                        .build()
        ).willReturn(
                Jwt.withTokenValue("new-refresh-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );

        LoginResponse response = authService.refresh(new RefreshTokenRequest("refresh-token"));

        assertThat(response.refreshToken()).isEqualTo("new-refresh-token");
        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-token")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("리프레시는 저장소에 없는 refresh token이면 UnauthorizedException을 던진다")
    void refreshThrowsWhenTokenIsUnknown() {
        AuthService authService = createAuthService();

        given(jwtDecoder.decode("refresh-token")).willReturn(
                Jwt.withTokenValue("refresh-token")
                        .header("alg", "HS256")
                        .claim("tokenType", "refresh")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );
        given(refreshTokenStore.findBySubject("1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-token")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("리프레시는 저장된 owner와 subject가 다르면 UnauthorizedException을 던진다")
    void refreshThrowsWhenOwnerDoesNotMatch() {
        AuthService authService = createAuthService();

        given(jwtDecoder.decode("refresh-token")).willReturn(
                Jwt.withTokenValue("refresh-token")
                        .header("alg", "HS256")
                        .claim("tokenType", "refresh")
                        .subject("1")
                        .issuedAt(clock.instant())
                        .expiresAt(clock.instant().plusSeconds(1209600))
                        .build()
        );
        given(refreshTokenStore.findBySubject("1"))
                .willReturn(Optional.of(new RefreshTokenSession("2", tokenHash("refresh-token"))));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("refresh-token")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("리프레시는 만료된 refresh token이면 UnauthorizedException을 던진다")
    void refreshThrowsWhenTokenIsExpired() {
        AuthService authService = createAuthService();
        given(jwtDecoder.decode("expired-refresh-token")).willThrow(new BadJwtException("expired token"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("expired-refresh-token")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("리프레시는 잘못된 refresh token이면 UnauthorizedException을 던진다")
    void refreshThrowsWhenTokenIsMalformed() {
        AuthService authService = createAuthService();
        given(jwtDecoder.decode("broken-token")).willThrow(new BadJwtException("bad token"));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("broken-token")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("중복 이메일 회원가입은 BadRequestException을 던진다")
    void registerThrowsWhenEmailAlreadyExists() {
        AuthService authService = createAuthService();
        given(userRepository.existsByEmail("bikeoasis@example.com")).willReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("bikeoasis@example.com", "example-password", "bikeoasis", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");
    }

    @Test
    @DisplayName("legacy externalId를 주면 기존 사용자 레코드에 실제 계정을 연결한다")
    void registerClaimsLegacyUserWhenExternalIdProvided() {
        AuthService authService = createAuthService();
        UserEntity legacyUser = new UserEntity("legacy-device-1", null, null, "old-name", null);
        ReflectionTestUtils.setField(legacyUser, "id", 1L);
        given(userRepository.existsByEmail("bikeoasis@example.com")).willReturn(false);
        given(userRepository.findByExternalId("legacy-device-1")).willReturn(Optional.of(legacyUser));
        given(userRepository.save(any(UserEntity.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(jwtEncoder.encode(any(JwtEncoderParameters.class))).willReturn(
                Jwt.withTokenValue("jwt-token")
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

        LoginResponse response = authService.register(new RegisterRequest("bikeoasis@example.com", "example-password", "bikeoasis", null, "legacy-device-1"));

        assertThat(response.userId()).isEqualTo(1L);
        assertThat(legacyUser.getEmail()).isEqualTo("bikeoasis@example.com");
        assertThat(legacyUser.getPasswordHash()).isNotBlank();
    }

    @Test
    @DisplayName("현재 사용자 조회는 subject로 사용자를 찾는다")
    void getCurrentUserReturnsAuthenticatedUser() {
        AuthService authService = createAuthService();
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        assertThat(authService.getCurrentUser("1").email()).isEqualTo("bikeoasis@example.com");
    }

    @Test
    @DisplayName("subject가 숫자가 아니면 UnauthorizedException을 던진다")
    void findUserBySubjectThrowsWhenSubjectIsInvalid() {
        AuthService authService = createAuthService();

        assertThatThrownBy(() -> authService.findUserBySubject("not-a-number"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("로그인은 비밀번호가 다르면 UnauthorizedException을 던진다")
    void loginThrowsWhenPasswordDoesNotMatch() {
        AuthService authService = createAuthService();
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findByEmail("bikeoasis@example.com")).willReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bikeoasis@example.com", "wrong-password")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    private AuthService createAuthService() {
        return createAuthService(refreshTokenStore);
    }

    private AuthService createAuthService(RefreshTokenStore store) {
        return new AuthService(userRepository, store, jwtEncoder, jwtDecoder, passwordEncoder, clock, "bike-back-test", 900L, 1209600L);
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

    private static final class InMemoryRefreshTokenStore implements RefreshTokenStore {

        private final Map<String, RefreshTokenSession> sessions = new HashMap<>();

        @Override
        public Optional<RefreshTokenSession> findBySubject(String subject) {
            return Optional.ofNullable(sessions.get(subject));
        }

        @Override
        public void save(String subject, RefreshTokenSession session, Duration ttl) {
            sessions.put(subject, session);
        }
    }
}
