package com.bikeprojectminji.bikeback.auth.service;

import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {

    private static final String TOKEN_TYPE_BEARER = "Bearer";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final RefreshTokenStore refreshTokenStore;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final String issuer;
    private final long accessTokenValiditySec;
    private final long refreshTokenValiditySec;

    public AuthTokenService(
            RefreshTokenStore refreshTokenStore,
            JwtEncoder jwtEncoder,
            Clock clock,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.token-validity-sec}") long accessTokenValiditySec,
            @Value("${auth.jwt.refresh-token-validity-sec:1209600}") long refreshTokenValiditySec
    ) {
        this.refreshTokenStore = refreshTokenStore;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.issuer = issuer;
        this.accessTokenValiditySec = accessTokenValiditySec;
        this.refreshTokenValiditySec = refreshTokenValiditySec;
    }

    public LoginResponse issueLoginResponse(UserEntity user) {
        // 모든 로그인 경로가 동일한 토큰 응답 계약을 쓰도록 발급과 refresh 저장을 한곳에서 처리한다.
        String accessToken = issueToken(user, TOKEN_TYPE_ACCESS, accessTokenValiditySec);
        String refreshToken = issueToken(user, TOKEN_TYPE_REFRESH, refreshTokenValiditySec);
        saveRefreshTokenSession(user, refreshToken);
        return new LoginResponse(
                TOKEN_TYPE_BEARER,
                accessToken,
                refreshToken,
                accessTokenValiditySec,
                refreshTokenValiditySec,
                user.getId(),
                user.getDisplayName()
        );
    }

    public void validateStoredRefreshToken(Jwt jwt, String refreshToken) {
        // 저장소의 마지막 refresh token 해시와 요청 토큰을 비교해 재사용/위조를 막는다.
        RefreshTokenSession storedSession = refreshTokenStore.findBySubject(jwt.getSubject())
                .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다."));

        if (!jwt.getSubject().equals(storedSession.subject())) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }

        if (!hashToken(refreshToken).equals(storedSession.tokenHash())) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }
    }

    public void deleteRefreshToken(String subject) {
        refreshTokenStore.delete(subject);
    }

    private String issueToken(UserEntity user, String tokenType, long validitySec) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(validitySec);

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))
                .claim("tokenType", tokenType)
                .claim("displayName", user.getDisplayName());
        if (user.getEmail() != null) {
            claimsBuilder.claim("email", user.getEmail());
        }

        if (TOKEN_TYPE_REFRESH.equals(tokenType)) {
            claimsBuilder.claim("jti", UUID.randomUUID().toString());
        }

        JwtClaimsSet claims = claimsBuilder.build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void saveRefreshTokenSession(UserEntity user, String refreshToken) {
        refreshTokenStore.save(
                String.valueOf(user.getId()),
                new RefreshTokenSession(String.valueOf(user.getId()), hashToken(refreshToken)),
                Duration.ofSeconds(refreshTokenValiditySec)
        );
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte current : bytes) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("refresh token 해시에 실패했습니다.", exception);
        }
    }
}
