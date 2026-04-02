package com.bikeprojectminji.bikeback.service.auth;

import com.bikeprojectminji.bikeback.dto.auth.AuthMeResponse;
import com.bikeprojectminji.bikeback.dto.auth.LoginRequest;
import com.bikeprojectminji.bikeback.dto.auth.LoginResponse;
import com.bikeprojectminji.bikeback.entity.user.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import com.bikeprojectminji.bikeback.repository.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final String issuer;
    private final long tokenValiditySec;

    public AuthService(
            UserRepository userRepository,
            JwtEncoder jwtEncoder,
            Clock clock,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.token-validity-sec}") long tokenValiditySec
    ) {
        this.userRepository = userRepository;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.issuer = issuer;
        this.tokenValiditySec = tokenValiditySec;
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userRepository.findByExternalId(request.externalId())
                .map(existing -> updateUser(existing, request))
                .orElseGet(() -> new UserEntity(request.externalId(), request.displayName(), request.profileImageUrl()));

        UserEntity savedUser = userRepository.save(user);
        String accessToken = issueToken(savedUser);

        return new LoginResponse("Bearer", accessToken, tokenValiditySec, savedUser.getId(), savedUser.getDisplayName());
    }

    public AuthMeResponse getCurrentUser(String subject) {
        UserEntity user = findUserBySubject(subject);
        return new AuthMeResponse(user.getId(), user.getDisplayName(), true, "USER");
    }

    public UserEntity findUserBySubject(String subject) {
        Long userId;
        try {
            userId = Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }

        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다."));
    }

    private UserEntity updateUser(UserEntity existingUser, LoginRequest request) {
        existingUser.updateProfile(request.displayName(), request.profileImageUrl());
        return existingUser;
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
                .claim("externalId", user.getExternalId())
                .claim("displayName", user.getDisplayName())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
