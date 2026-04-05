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
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }

        UserEntity savedUser = userRepository.save(resolveRegisterUser(request));
        String accessToken = issueToken(savedUser);
        return new LoginResponse("Bearer", accessToken, tokenValiditySec, savedUser.getId(), savedUser.getDisplayName());
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = issueToken(user);
        return new LoginResponse("Bearer", accessToken, tokenValiditySec, user.getId(), user.getDisplayName());
    }

    public AuthMeResponse getCurrentUser(String subject) {
        UserEntity user = findUserBySubject(subject);
        return new AuthMeResponse(user.getId(), user.getEmail(), user.getDisplayName(), true, "USER");
    }

    public UserEntity findUserBySubject(String subject) {
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
