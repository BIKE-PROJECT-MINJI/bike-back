package com.bikeprojectminji.bikeback.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.dto.LoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.dto.RegisterRequest;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtEncoder jwtEncoder;

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-02T00:00:00Z"), ZoneOffset.UTC);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("회원가입은 사용자를 저장하고 JWT를 발급한다")
    void registerReturnsIssuedJwt() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);
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
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build()
        );

        LoginResponse response = authService.register(request);

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.displayName()).isEqualTo("bikeoasis");
    }

    @Test
    @DisplayName("로그인은 이메일과 비밀번호를 검증하고 JWT를 발급한다")
    void loginReturnsIssuedJwt() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);
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
                        .expiresAt(clock.instant().plusSeconds(3600))
                        .build()
        );

        LoginResponse response = authService.login(request);

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("중복 이메일 회원가입은 BadRequestException을 던진다")
    void registerThrowsWhenEmailAlreadyExists() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);
        given(userRepository.existsByEmail("bikeoasis@example.com")).willReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("bikeoasis@example.com", "example-password", "bikeoasis", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");
    }

    @Test
    @DisplayName("legacy externalId를 주면 기존 사용자 레코드에 실제 계정을 연결한다")
    void registerClaimsLegacyUserWhenExternalIdProvided() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);
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
                        .expiresAt(clock.instant().plusSeconds(3600))
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
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        assertThat(authService.getCurrentUser("1").email()).isEqualTo("bikeoasis@example.com");
    }

    @Test
    @DisplayName("subject가 숫자가 아니면 UnauthorizedException을 던진다")
    void findUserBySubjectThrowsWhenSubjectIsInvalid() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);

        assertThatThrownBy(() -> authService.findUserBySubject("not-a-number"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }

    @Test
    @DisplayName("로그인은 비밀번호가 다르면 UnauthorizedException을 던진다")
    void loginThrowsWhenPasswordDoesNotMatch() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, passwordEncoder, clock, "bike-back-test", 3600L);
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", passwordEncoder.encode("example-password"), "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findByEmail("bikeoasis@example.com")).willReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bikeoasis@example.com", "wrong-password")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
