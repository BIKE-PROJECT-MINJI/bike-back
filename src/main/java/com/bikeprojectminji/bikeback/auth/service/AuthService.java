package com.bikeprojectminji.bikeback.auth.service;

import com.bikeprojectminji.bikeback.auth.dto.AuthMeResponse;
import com.bikeprojectminji.bikeback.auth.dto.KakaoLoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.dto.RefreshTokenRequest;
import com.bikeprojectminji.bikeback.auth.dto.RegisterRequest;
import com.bikeprojectminji.bikeback.auth.entity.KakaoAccountLinkEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserConsentEntity;
import com.bikeprojectminji.bikeback.beta.service.BetaInvitationService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.UnauthorizedException;
import com.bikeprojectminji.bikeback.auth.repository.KakaoAccountLinkRepository;
import com.bikeprojectminji.bikeback.auth.repository.UserConsentRepository;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String TOKEN_TYPE_REFRESH = "refresh";

    // 사용자 계정 aggregate는 auth 도메인이 소유하고,
    // 다른 도메인은 현재 사용자 식별/조회가 필요할 때 이 서비스를 통해 접근한다.

    private final UserRepository userRepository;
    private final KakaoAccountLinkRepository kakaoAccountLinkRepository;
    private final UserConsentRepository userConsentRepository;
    private final AccountDeletionService accountDeletionService;
    private final BetaInvitationService betaInvitationService;
    private final AuthTokenService authTokenService;
    private final KakaoAccountClient kakaoAccountClient;
    private final JwtDecoder jwtDecoder;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AuthService(
            UserRepository userRepository,
            KakaoAccountLinkRepository kakaoAccountLinkRepository,
            UserConsentRepository userConsentRepository,
            AccountDeletionService accountDeletionService,
            BetaInvitationService betaInvitationService,
            AuthTokenService authTokenService,
            KakaoAccountClient kakaoAccountClient,
            JwtDecoder jwtDecoder,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.kakaoAccountLinkRepository = kakaoAccountLinkRepository;
        this.userConsentRepository = userConsentRepository;
        this.accountDeletionService = accountDeletionService;
        this.betaInvitationService = betaInvitationService;
        this.authTokenService = authTokenService;
        this.kakaoAccountClient = kakaoAccountClient;
        this.jwtDecoder = jwtDecoder;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public LoginResponse register(RegisterRequest request) {
        // 회원가입은 이메일 중복을 먼저 막고,
        // 정상 사용자면 저장 직후 바로 access token까지 발급해 앱이 추가 로그인 없이 진입하도록 한다.
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("이미 사용 중인 이메일입니다.");
        }

        UserEntity savedUser = userRepository.save(resolveRegisterUser(request));
        grantBetaAccessIfInviteCodeProvided(request, savedUser);
        return authTokenService.issueLoginResponse(savedUser);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // 로그인은 이메일로 사용자를 찾고, 저장된 passwordHash와 현재 입력 비밀번호를 비교한다.
        // 한쪽이라도 맞지 않으면 같은 예외 메시지로 응답해 계정 존재 여부를 노출하지 않는다.
        UserEntity user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (user.isDeleted()) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        return authTokenService.issueLoginResponse(user);
    }

    @Transactional
    public LoginResponse kakaoLogin(KakaoLoginRequest request) {
        String ageBand = resolveAgeBand(request.birthDate());
        KakaoAccountProfile profile = kakaoAccountClient.fetchProfile(request.kakaoAccessToken());
        UserEntity user = kakaoAccountLinkRepository.findByProviderUserId(profile.providerUserId())
                .map(link -> updateLinkedKakaoUser(link, profile))
                .orElseGet(() -> createKakaoUser(profile));
        upsertUserConsent(user.getId(), request, ageBand);
        return authTokenService.issueLoginResponse(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse refresh(RefreshTokenRequest request) {
        // refresh 요청은 서명/만료 검증을 통과한 refresh token만 받아야 하므로,
        // decode 단계에서 실패하거나 tokenType이 다르면 동일한 401 계약으로 끊는다.
        try {
            org.springframework.security.oauth2.jwt.Jwt jwt = jwtDecoder.decode(request.refreshToken());
            validateRefreshToken(jwt);
            authTokenService.validateStoredRefreshToken(jwt, request.refreshToken());
            UserEntity user = findUserBySubject(jwt.getSubject());
            return authTokenService.issueLoginResponse(user);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }
    }

    @Transactional(readOnly = true)
    public AuthMeResponse getCurrentUser(String subject) {
        // 이미 인증된 subject를 현재 사용자 aggregate로 해석하고,
        // 앱에서 바로 쓸 수 있는 최소 프로필 정보로 축약해 반환한다.
        UserEntity user = findUserBySubject(subject);
        return new AuthMeResponse(user.getId(), user.getEmail(), user.getDisplayName(), true, "USER");
    }

    public void logout(String subject) {
        UserEntity user = findUserBySubject(subject);
        authTokenService.deleteRefreshToken(String.valueOf(user.getId()));
    }

    @Transactional
    public void deleteCurrentUser(String subject) {
        UserEntity user = findUserBySubject(subject);
        accountDeletionService.deleteOwnedData(user.getId());
        kakaoAccountLinkRepository.deleteByUserId(user.getId());
        userConsentRepository.deleteByUserId(user.getId());
        authTokenService.deleteRefreshToken(String.valueOf(user.getId()));
        user.markDeleted(clock);
    }

    @Transactional(readOnly = true)
    public UserEntity findUserBySubject(String subject) {
        // 현재 토큰 subject는 숫자 userId일 수도 있고, 레거시 externalId일 수도 있다.
        // 두 경로를 모두 허용해 이전 토큰과 새 토큰의 연속성을 유지한다.
        try {
            Long userId = Long.valueOf(subject);
            return requireActiveUser(userRepository.findById(userId)
                    .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다.")));
        } catch (NumberFormatException exception) {
            return requireActiveUser(userRepository.findByExternalId(subject)
                    .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다.")));
        }
    }

    private UserEntity requireActiveUser(UserEntity user) {
        if (user.isDeleted()) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }
        return user;
    }

    private UserEntity updateLinkedKakaoUser(KakaoAccountLinkEntity link, KakaoAccountProfile profile) {
        UserEntity user = userRepository.findById(link.getUserId())
                .orElseThrow(() -> new UnauthorizedException("로그인 정보가 필요합니다."));
        if (user.isDeleted()) {
            throw new UnauthorizedException("삭제된 계정입니다.");
        }
        user.updateKakaoProfile(resolveKakaoDisplayName(profile), profile.profileImageUrl());
        return user;
    }

    private UserEntity createKakaoUser(KakaoAccountProfile profile) {
        UserEntity user = userRepository.save(new UserEntity(
                "kakao:" + profile.providerUserId(),
                null,
                null,
                resolveKakaoDisplayName(profile),
                profile.profileImageUrl()
        ));
        kakaoAccountLinkRepository.save(new KakaoAccountLinkEntity(user.getId(), profile.providerUserId(), clock));
        return user;
    }

    private void upsertUserConsent(Long userId, KakaoLoginRequest request, String ageBand) {
        UserConsentEntity consent = userConsentRepository.findByUserId(userId)
                .orElseGet(() -> new UserConsentEntity(
                        userId,
                        request.privacyPolicyVersion(),
                        request.termsVersion(),
                        request.locationTermsVersion(),
                        ageBand,
                        clock
                ));
        consent.updateVersions(request.privacyPolicyVersion(), request.termsVersion(), request.locationTermsVersion(), ageBand, clock);
        userConsentRepository.save(consent);
    }

    private String resolveAgeBand(String birthDateText) {
        if (birthDateText == null || birthDateText.isBlank()) {
            throw new BadRequestException("birthDate는 비어 있을 수 없습니다.");
        }
        try {
            LocalDate birthDate = LocalDate.parse(birthDateText);
            int age = Period.between(birthDate, LocalDate.now(clock)).getYears();
            if (age < 14) {
                throw new BadRequestException("만 14세 이상만 가입할 수 있습니다.");
            }
            return age >= 19 ? "ADULT" : "TEEN";
        } catch (DateTimeException exception) {
            throw new BadRequestException("birthDate는 yyyy-MM-dd 형식이어야 합니다.");
        }
    }

    private String resolveKakaoDisplayName(KakaoAccountProfile profile) {
        if (profile.nickname() != null && !profile.nickname().isBlank()) {
            return profile.nickname();
        }
        return "gaja-rider";
    }

    private void validateRefreshToken(org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (!TOKEN_TYPE_REFRESH.equals(jwt.getClaimAsString("tokenType"))) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }
        if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new UnauthorizedException("로그인 정보가 필요합니다.");
        }
    }

    private UserEntity resolveRegisterUser(RegisterRequest request) {
        // legacyExternalId가 없으면 완전 신규 계정 생성이고,
        // 있으면 과거 레거시 계정을 실제 로컬 계정으로 승격(claim)하는 흐름이다.
        String passwordHash = passwordEncoder.encode(request.password());
        if (request.legacyExternalId() == null || request.legacyExternalId().isBlank()) {
            return new UserEntity(UUID.randomUUID().toString(), request.email(), passwordHash, request.displayName(), request.profileImageUrl());
        }

        UserEntity legacyUser = userRepository.findByExternalId(request.legacyExternalId())
                .orElseThrow(() -> new BadRequestException("이전 계정 정보를 찾을 수 없습니다."));
        if (legacyUser.getEmail() != null && !legacyUser.getEmail().isBlank()) {
            throw new BadRequestException("이미 실제 계정으로 전환된 사용자입니다.");
        }
        legacyUser.claimLocalAccount(request.email(), passwordHash, request.displayName(), request.profileImageUrl());
        return legacyUser;
    }

    private void grantBetaAccessIfInviteCodeProvided(RegisterRequest request, UserEntity user) {
        if (request.inviteCode() == null || request.inviteCode().isBlank()) {
            return;
        }
        betaInvitationService.consumeForUser(request.inviteCode(), user.getId());
        user.grantBetaAccess();
    }
}
