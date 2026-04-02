package com.bikeprojectminji.bikeback.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.dto.auth.LoginRequest;
import com.bikeprojectminji.bikeback.dto.auth.LoginResponse;
import com.bikeprojectminji.bikeback.entity.user.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import com.bikeprojectminji.bikeback.repository.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @Test
    @DisplayName("로그인은 사용자를 저장하고 JWT를 발급한다")
    void loginReturnsIssuedJwt() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, clock, "bike-back-test", 3600L);
        LoginRequest request = new LoginRequest("device-1", "bikeoasis", null);
        UserEntity savedUser = new UserEntity("device-1", "bikeoasis", null);
        ReflectionTestUtils.setField(savedUser, "id", 1L);

        given(userRepository.findByExternalId("device-1")).willReturn(Optional.empty());
        given(userRepository.save(any(UserEntity.class))).willReturn(savedUser);
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
        assertThat(response.displayName()).isEqualTo("bikeoasis");
    }

    @Test
    @DisplayName("현재 사용자 조회는 subject로 사용자를 찾는다")
    void getCurrentUserReturnsAuthenticatedUser() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, clock, "bike-back-test", 3600L);
        UserEntity user = new UserEntity("device-1", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        assertThat(authService.getCurrentUser("1").userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("subject가 숫자가 아니면 UnauthorizedException을 던진다")
    void findUserBySubjectThrowsWhenSubjectIsInvalid() {
        AuthService authService = new AuthService(userRepository, jwtEncoder, clock, "bike-back-test", 3600L);

        assertThatThrownBy(() -> authService.findUserBySubject("not-a-number"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("로그인 정보가 필요합니다.");
    }
}
