package com.bikeprojectminji.bikeback.auth.service;

import com.bikeprojectminji.bikeback.auth.dto.AuthMeResponse;
import com.bikeprojectminji.bikeback.auth.dto.LoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.dto.RegisterRequest;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    // 사용자 계정 aggregate는 auth 도메인이 소유하고,
    // 다른 도메인은 현재 사용자 식별/조회가 필요할 때 이 서비스를 통해 접근한다.

    private final UserRepository userRepository;
    private final JwtEncoder jwtEncoder;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String issuer;
    private final long tokenValiditySec;

    public AuthService(
            UserRepository userRepository,
            JwtEncoder jwtEncoder,
            PasswordEncoder passwordEncoder,
            Clock clock,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.token-validity-sec}") long tokenValiditySec
    ) {
        this.userRepository = userRepository;
        this.jwtEncoder = jwtEncoder;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.issuer = issuer;
        this.tokenValiditySec = tokenValiditySec;
    }

    public LoginResponse register(RegisterRequest request) {
        // 회원가입은 이메일 중복을 먼저 막고,
        // 정상 사용자면 저장 직후 바로 access token까지 발급해 앱이 추가 로그인 없이 진입하도록 한다.
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }

        UserEntity savedUser = userRepository.save(resolveRegisterUser(request));
        String accessToken = issueToken(savedUser);
        return new LoginResponse("Bearer", accessToken, tokenValiditySec, savedUser.getId(), savedUser.getDisplayName());
    }

    public LoginResponse login(LoginRequest request) {
        // 로그인은 이메일로 사용자를 찾고, 저장된 passwordHash와 현재 입력 비밀번호를 비교한다.
        // 한쪽이라도 맞지 않으면 같은 예외 메시지로 응답해 계정 존재 여부를 노출하지 않는다.
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = issueToken(user);
        return new LoginResponse("Bearer", accessToken, tokenValiditySec, user.getId(), user.getDisplayName());
    }

    public AuthMeResponse getCurrentUser(String subject) {
        // 이미 인증된 subject를 현재 사용자 aggregate로 해석하고,
        // 앱에서 바로 쓸 수 있는 최소 프로필 정보로 축약해 반환한다.
        UserEntity user = findUserBySubject(subject);
        return new AuthMeResponse(user.getId(), user.getEmail(), user.getDisplayName(), true, "USER");
    }

    public UserEntity findUserBySubject(String subject) {
        // 현재 토큰 subject는 숫자 userId일 수도 있고, 레거시 externalId일 수도 있다.
        // 두 경로를 모두 허용해 이전 토큰과 새 토큰의 연속성을 유지한다.
        try {
            Long userId = Long.valueOf(subject);
            return userRepository.findById(userId)
                    .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다."));
        } catch (NumberFormatException exception) {
            return userRepository.findByExternalId(subject)
                    .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다."));
        }
    }

    private String issueToken(UserEntity user) {
        // 토큰은 현재 userId를 subject로 고정하고,
        // 앱이 자주 쓰는 email/displayName만 claim으로 최소 포함한다.
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(tokenValiditySec);

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("displayName", user.getDisplayName())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private UserEntity resolveRegisterUser(RegisterRequest request) {
        // legacyExternalId가 없으면 완전 신규 계정 생성이고,
        // 있으면 과거 레거시 계정을 실제 로컬 계정으로 승격(claim)하는 흐름이다.
        String passwordHash = passwordEncoder.encode(request.password());
        if (request.legacyExternalId() == null || request.legacyExternalId().isBlank()) {
            return new UserEntity(null, request.email(), passwordHash, request.displayName(), request.profileImageUrl());
        }

        UserEntity legacyUser = userRepository.findByExternalId(request.legacyExternalId())
                .orElseThrow(() -> new BadRequestException("이전 계정 정보를 찾을 수 없습니다."));
        if (legacyUser.getEmail() != null && !legacyUser.getEmail().isBlank()) {
            throw new BadRequestException("이미 실제 계정으로 전환된 사용자입니다.");
        }
        legacyUser.claimLocalAccount(request.email(), passwordHash, request.displayName(), request.profileImageUrl());
        return legacyUser;
    }
}
