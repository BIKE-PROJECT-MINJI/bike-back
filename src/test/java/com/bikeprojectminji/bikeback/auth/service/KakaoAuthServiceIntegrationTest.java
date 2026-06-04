package com.bikeprojectminji.bikeback.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.auth.dto.KakaoLoginRequest;
import com.bikeprojectminji.bikeback.auth.dto.LoginResponse;
import com.bikeprojectminji.bikeback.auth.entity.KakaoAccountLinkEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserConsentEntity;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.KakaoAccountLinkRepository;
import com.bikeprojectminji.bikeback.auth.repository.UserConsentRepository;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.event.entity.ClientEventEntity;
import com.bikeprojectminji.bikeback.event.repository.ClientEventRepository;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordProcessedPointEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KakaoAuthServiceIntegrationTest {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final KakaoAccountLinkRepository kakaoAccountLinkRepository;
    private final UserConsentRepository userConsentRepository;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final CourseRepository courseRepository;
    private final CourseRoutePointRepository courseRoutePointRepository;
    private final ClientEventRepository clientEventRepository;
    private final ObjectMapper objectMapper;
    private final InMemoryRefreshTokenStore refreshTokenStore;

    @Autowired
    KakaoAuthServiceIntegrationTest(
            AuthService authService,
            UserRepository userRepository,
            KakaoAccountLinkRepository kakaoAccountLinkRepository,
            UserConsentRepository userConsentRepository,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            CourseRepository courseRepository,
            CourseRoutePointRepository courseRoutePointRepository,
            ClientEventRepository clientEventRepository,
            ObjectMapper objectMapper,
            RefreshTokenStore refreshTokenStore
    ) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.kakaoAccountLinkRepository = kakaoAccountLinkRepository;
        this.userConsentRepository = userConsentRepository;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.courseRepository = courseRepository;
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.clientEventRepository = clientEventRepository;
        this.objectMapper = objectMapper;
        this.refreshTokenStore = (InMemoryRefreshTokenStore) refreshTokenStore;
    }

    @Test
    @DisplayName("카카오 로그인은 신규 사용자, 카카오 연결, 동의 버전, refresh session을 함께 생성한다")
    void kakaoLoginCreatesUserLinkConsentAndRefreshSession() {
        LoginResponse response = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-2026-05-24",
                "terms-2026-05-24",
                "location-2026-05-24",
                "2000-01-01"
        ));

        UserEntity user = userRepository.findById(response.userId()).orElseThrow();
        KakaoAccountLinkEntity link = kakaoAccountLinkRepository.findByProviderUserId("123456789").orElseThrow();
        UserConsentEntity consent = userConsentRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(user.getDisplayName()).isEqualTo("gaja-rider");
        assertThat(user.getEmail()).isNull();
        assertThat(link.getUserId()).isEqualTo(user.getId());
        assertThat(consent.getPrivacyPolicyVersion()).isEqualTo("privacy-2026-05-24");
        assertThat(consent.getTermsVersion()).isEqualTo("terms-2026-05-24");
        assertThat(consent.getLocationTermsVersion()).isEqualTo("location-2026-05-24");
        assertThat(consent.isAgeVerified()).isTrue();
        assertThat(consent.getAgeBand()).isEqualTo("ADULT");
        assertThat(consent.getAgeVerifiedAt()).isNotNull();
        assertThat(refreshTokenStore.findBySubject(String.valueOf(user.getId()))).isPresent();
    }

    @Test
    @DisplayName("카카오 로그인은 만 14세 미만 사용자를 차단한다")
    void kakaoLoginRejectsUnderFourteenUser() {
        assertThatThrownBy(() -> authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v1",
                "terms-v1",
                "location-v1",
                "2015-01-01"
        )))
                .hasMessage("만 14세 이상만 가입할 수 있습니다.");
    }

    @Test
    @DisplayName("계정 삭제 후 같은 카카오 계정으로 다시 가입할 수 있다")
    void kakaoLoginAllowsRejoinAfterAccountDeletion() {
        LoginResponse first = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v1",
                "terms-v1",
                "location-v1",
                "2000-01-01"
        ));
        authService.deleteCurrentUser(String.valueOf(first.userId()));

        LoginResponse second = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v2",
                "terms-v2",
                "location-v2",
                "2000-01-01"
        ));

        UserEntity rejoinedUser = userRepository.findById(second.userId()).orElseThrow();
        assertThat(second.userId()).isNotEqualTo(first.userId());
        assertThat(rejoinedUser.isDeleted()).isFalse();
        assertThat(kakaoAccountLinkRepository.findByProviderUserId("123456789"))
                .map(KakaoAccountLinkEntity::getUserId)
                .contains(second.userId());
    }

    @Test
    @DisplayName("카카오 로그인은 기존 카카오 연결이 있으면 같은 사용자를 재사용하고 프로필만 갱신한다")
    void kakaoLoginReusesExistingLinkedUser() {
        LoginResponse first = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v1",
                "terms-v1",
                "location-v1"
        ));

        LoginResponse second = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-updated-profile",
                "privacy-v2",
                "terms-v2",
                "location-v2"
        ));

        UserEntity user = userRepository.findById(first.userId()).orElseThrow();
        UserConsentEntity consent = userConsentRepository.findByUserId(first.userId()).orElseThrow();

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(user.getDisplayName()).isEqualTo("updated-rider");
        assertThat(consent.getPrivacyPolicyVersion()).isEqualTo("privacy-v2");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("카카오 로그인은 테스트 트랜잭션 없이도 기존 사용자 프로필 갱신을 DB에 반영한다")
    void kakaoLoginPersistsLinkedUserProfileUpdateWithoutCallerTransaction() {
        LoginResponse first = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v1",
                "terms-v1",
                "location-v1"
        ));

        authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-updated-profile",
                "privacy-v2",
                "terms-v2",
                "location-v2"
        ));

        UserEntity user = userRepository.findById(first.userId()).orElseThrow();

        assertThat(user.getDisplayName()).isEqualTo("updated-rider");
    }

    @Test
    @DisplayName("계정 삭제는 카카오 연결과 동의 기록을 제거하고 refresh session을 폐기한다")
    void deleteAccountRevokesKakaoLinkConsentAndRefreshSession() {
        LoginResponse response = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v1",
                "terms-v1",
                "location-v1"
        ));

        authService.deleteCurrentUser(String.valueOf(response.userId()));

        UserEntity user = userRepository.findById(response.userId()).orElseThrow();
        assertThat(user.isDeleted()).isTrue();
        assertThat(kakaoAccountLinkRepository.findByUserId(response.userId())).isEmpty();
        assertThat(userConsentRepository.findByUserId(response.userId())).isEmpty();
        assertThat(refreshTokenStore.findBySubject(String.valueOf(response.userId()))).isEmpty();
    }

    @Test
    @DisplayName("계정 삭제는 소유 주행 원본과 비공개 코스를 삭제하고 공개 코스는 익명화한다")
    void deleteAccountRemovesOwnedRideDataAndAnonymizesPublicCourses() {
        LoginResponse response = authService.kakaoLogin(new KakaoLoginRequest(
                "kakao-access-token-new",
                "privacy-v1",
                "terms-v1",
                "location-v1"
        ));
        Long userId = response.userId();
        OffsetDateTime now = OffsetDateTime.parse("2026-05-27T23:00:00+09:00");

        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(userId, "client-ride-r3", now.minusMinutes(30), now, 1200, 900));
        rideRecordPointRepository.save(new RideRecordPointEntity(rideRecord.getId(), 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)));
        rideRecordProcessedPointRepository.save(new RideRecordProcessedPointEntity(rideRecord.getId(), 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)));

        CourseEntity publicCourse = courseRepository.save(new CourseEntity(
                "공개 유지 코스", "익명화 대상", BigDecimal.valueOf(1.2), 15, 1, false, null,
                BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780), userId, rideRecord.getId(), CourseVisibility.PUBLIC
        ));
        publicCourse.updateShareToken("public-share-token");
        CourseEntity privateCourse = courseRepository.save(new CourseEntity(
                "비공개 삭제 코스", "삭제 대상", BigDecimal.valueOf(1.0), 10, 2, false, null,
                BigDecimal.valueOf(37.5666), BigDecimal.valueOf(126.9781), userId, null, CourseVisibility.PRIVATE
        ));
        courseRoutePointRepository.save(new CourseRoutePointEntity(publicCourse.getId(), 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)));
        courseRoutePointRepository.save(new CourseRoutePointEntity(privateCourse.getId(), 1, BigDecimal.valueOf(37.5666), BigDecimal.valueOf(126.9781)));
        clientEventRepository.save(new ClientEventEntity(
                "ride_debug_event",
                1,
                userId,
                "session-r3",
                now,
                now,
                "ride",
                publicCourse.getId(),
                rideRecord.getId(),
                "1.0.0",
                "android",
                "mobile",
                "granted",
                objectMapper.createObjectNode().put("reason", "before_delete"),
                now
        ));

        authService.deleteCurrentUser(String.valueOf(userId));

        CourseEntity anonymizedPublicCourse = courseRepository.findById(publicCourse.getId()).orElseThrow();
        assertThat(rideRecordRepository.findById(rideRecord.getId())).isEmpty();
        assertThat(rideRecordPointRepository.countByRideRecordId(rideRecord.getId())).isZero();
        assertThat(rideRecordProcessedPointRepository.countByRideRecordId(rideRecord.getId())).isZero();
        assertThat(courseRepository.findById(privateCourse.getId())).isEmpty();
        assertThat(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(privateCourse.getId())).isEmpty();
        assertThat(anonymizedPublicCourse.getOwnerUserId()).isNull();
        assertThat(anonymizedPublicCourse.getSourceRideRecordId()).isNull();
        assertThat(anonymizedPublicCourse.getShareToken()).isNull();
        assertThat(clientEventRepository.countByUserId(userId)).isZero();
    }

    @TestConfiguration
    static class TestAuthConfiguration {

        @Bean
        @Primary
        KakaoAccountClient kakaoAccountClient() {
            return accessToken -> {
                if ("kakao-access-token-updated-profile".equals(accessToken)) {
                    return new KakaoAccountProfile("123456789", "updated-rider", "https://example.com/updated.png", "rider@example.com");
                }
                return new KakaoAccountProfile("123456789", "gaja-rider", "https://example.com/profile.png", "rider@example.com");
            };
        }

        @Bean
        @Primary
        RefreshTokenStore refreshTokenStore() {
            return new InMemoryRefreshTokenStore();
        }
    }

    static final class InMemoryRefreshTokenStore implements RefreshTokenStore {

        private final Map<String, RefreshTokenSession> sessions = new HashMap<>();

        @Override
        public Optional<RefreshTokenSession> findBySubject(String subject) {
            return Optional.ofNullable(sessions.get(subject));
        }

        @Override
        public void save(String subject, RefreshTokenSession session, Duration ttl) {
            sessions.put(subject, session);
        }

        @Override
        public void delete(String subject) {
            sessions.remove(subject);
        }
    }
}
