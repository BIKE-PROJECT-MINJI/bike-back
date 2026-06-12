package com.bikeprojectminji.bikeback.profile.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.repository.CourseActivityAggregate;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.profile.dto.ProfileActivitySummaryResponse;
import com.bikeprojectminji.bikeback.profile.dto.ProfileOverallActivitySummaryResponse;
import com.bikeprojectminji.bikeback.profile.dto.ProfileMeResponse;
import com.bikeprojectminji.bikeback.profile.dto.ProfileWeeklyActivitySummaryResponse;
import com.bikeprojectminji.bikeback.profile.dto.UpdatePreferenceRequest;
import com.bikeprojectminji.bikeback.profile.dto.UpdateProfileRequest;
import com.bikeprojectminji.bikeback.profile.dto.UserPreferenceResponse;
import com.bikeprojectminji.bikeback.profile.entity.BikeRoadPriority;
import com.bikeprojectminji.bikeback.profile.entity.UserPreferenceEntity;
import com.bikeprojectminji.bikeback.profile.repository.UserPreferenceRepository;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordFinalizationStatus;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordActivityAggregate;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {

    private static final long CURRENT_ELEVATION_PLACEHOLDER_M = 0L;
    private static final String USABLE_RIDE_FINALIZATION_STATUS = RideRecordFinalizationStatus.READY.name();

    // profile은 별도 aggregate를 만들지 않고,
    // auth 도메인이 소유한 사용자 계정 aggregate를 use case 단위로 조작한다.

    private final AuthService authService;
    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final RideRecordRepository rideRecordRepository;
    private final CourseRepository courseRepository;
    private final Clock clock;

    public ProfileService(
            AuthService authService,
            UserRepository userRepository,
            UserPreferenceRepository userPreferenceRepository,
            RideRecordRepository rideRecordRepository,
            CourseRepository courseRepository,
            Clock clock
    ) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.userPreferenceRepository = userPreferenceRepository;
        this.rideRecordRepository = rideRecordRepository;
        this.courseRepository = courseRepository;
        this.clock = clock;
    }

    public ProfileMeResponse getMyProfile(String subject) {
        // profile 조회는 profile 도메인이 직접 사용자를 식별하지 않고,
        // auth 도메인을 통해 현재 사용자 aggregate를 받아 응답용 DTO로 변환한다.
        UserEntity user = authService.findUserBySubject(subject);
        return toResponse(user);
    }

    public ProfileMeResponse updateMyProfile(String subject, UpdateProfileRequest request) {
        // profile 수정은 displayName / profileImageUrl만 다루고,
        // 사용자 계정 aggregate 저장 책임은 auth가 소유한 UserRepository를 그대로 사용한다.
        UserEntity user = authService.findUserBySubject(subject);
        user.updateProfile(request.displayName(), request.profileImageUrl());
        UserEntity savedUser = userRepository.save(user);
        return toResponse(savedUser);
    }

    public UserPreferenceResponse getMyPreference(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        return userPreferenceRepository.findByUserId(user.getId())
                .map(this::toPreferenceResponse)
                .orElseGet(this::defaultPreferenceResponse);
    }

    public UserPreferenceResponse updateMyPreference(String subject, UpdatePreferenceRequest request) {
        UserEntity user = authService.findUserBySubject(subject);
        UserPreferenceEntity preference = userPreferenceRepository.findByUserId(user.getId())
                .orElseGet(() -> UserPreferenceEntity.create(
                        user.getId(),
                        request.scenic(),
                        request.bikeRoadPriority(),
                        request.avoidDust(),
                        request.avoidUnsafeSurface(),
                        clock
                ));
        if (preference.getId() != null) {
            preference.update(
                    request.scenic(),
                    request.bikeRoadPriority(),
                    request.avoidDust(),
                    request.avoidUnsafeSurface(),
                    clock
            );
        }
        return toPreferenceResponse(userPreferenceRepository.save(preference));
    }

    public ProfileActivitySummaryResponse getMyActivitySummary(String subject) {
        // 활동 요약은 profile use case가 auth로 현재 사용자를 식별한 뒤,
        // ride/course 도메인의 집계 조회 메서드만 호출해 공통 응답 DTO로 묶어 반환한다.
        UserEntity user = authService.findUserBySubject(subject);
        ActivitySummaryWindow weeklyWindow = resolveCurrentWeekWindow();
        RideRecordActivityAggregate rideAggregate = rideRecordRepository.findActivityAggregateByOwnerUserIdAndFinalizationStatus(
                user.getId(),
                USABLE_RIDE_FINALIZATION_STATUS,
                weeklyWindow.start(),
                weeklyWindow.end()
        );
        CourseActivityAggregate courseAggregate = courseRepository.findActivityAggregateByOwnerUserId(
                user.getId(),
                weeklyWindow.start(),
                weeklyWindow.end()
        );
        ProfileWeeklyActivitySummaryResponse weeklySummary = buildWeeklySummary(rideAggregate, courseAggregate);
        ProfileOverallActivitySummaryResponse overallSummary = buildOverallSummary(rideAggregate);
        return new ProfileActivitySummaryResponse(weeklySummary, overallSummary);
    }

    private ProfileMeResponse toResponse(UserEntity user) {
        // 앱에서 필요한 최소 프로필 형태로만 잘라 응답해 도메인 entity가 직접 외부로 새지 않게 한다.
        return new ProfileMeResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getProfileImageUrl());
    }

    private UserPreferenceResponse toPreferenceResponse(UserPreferenceEntity preference) {
        return new UserPreferenceResponse(
                preference.isScenic(),
                preference.getBikeRoadPriority(),
                preference.isAvoidDust(),
                preference.isAvoidUnsafeSurface()
        );
    }

    private UserPreferenceResponse defaultPreferenceResponse() {
        return new UserPreferenceResponse(false, BikeRoadPriority.MEDIUM, false, false);
    }

    private ProfileWeeklyActivitySummaryResponse buildWeeklySummary(
            RideRecordActivityAggregate rideAggregate,
            CourseActivityAggregate courseAggregate
    ) {
        long rideCount = normalizeLong(rideAggregate == null ? null : rideAggregate.weeklyRideCount());
        long savedCourseCount = normalizeLong(courseAggregate == null ? null : courseAggregate.weeklyCourseCount());
        long totalDistanceM = normalizeLong(rideAggregate == null ? null : rideAggregate.weeklyDistanceM());
        long totalDurationSec = normalizeLong(rideAggregate == null ? null : rideAggregate.weeklyDurationSec());
        return new ProfileWeeklyActivitySummaryResponse(
                toKilometers(totalDistanceM),
                rideCount,
                toDurationMinutes(totalDurationSec),
                savedCourseCount
        );
    }

    private ProfileOverallActivitySummaryResponse buildOverallSummary(RideRecordActivityAggregate rideAggregate) {
        long rideCount = normalizeLong(rideAggregate == null ? null : rideAggregate.overallRideCount());
        long totalDistanceM = normalizeLong(rideAggregate == null ? null : rideAggregate.overallDistanceM());
        long totalDurationSec = normalizeLong(rideAggregate == null ? null : rideAggregate.overallDurationSec());
        return new ProfileOverallActivitySummaryResponse(
                toKilometers(totalDistanceM),
                rideCount,
                calculateAverageSpeedKmh(totalDistanceM, totalDurationSec),
                CURRENT_ELEVATION_PLACEHOLDER_M
        );
    }

    private ActivitySummaryWindow resolveCurrentWeekWindow() {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.ofHours(9));
        OffsetDateTime weekStart = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay()
                .atOffset(now.getOffset());
        return new ActivitySummaryWindow(weekStart, weekStart.plusWeeks(1).minusNanos(1));
    }

    private long normalizeLong(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal toKilometers(long distanceM) {
        return BigDecimal.valueOf(distanceM)
                .divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
    }

    private long toDurationMinutes(long totalDurationSec) {
        return totalDurationSec / 60;
    }

    private BigDecimal calculateAverageSpeedKmh(long totalDistanceM, long totalDurationSec) {
        if (totalDistanceM <= 0 || totalDurationSec <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        BigDecimal distanceKm = BigDecimal.valueOf(totalDistanceM)
                .divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        BigDecimal durationHours = BigDecimal.valueOf(totalDurationSec)
                .divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_UP);
        return distanceKm.divide(durationHours, 1, RoundingMode.HALF_UP);
    }

    private record ActivitySummaryWindow(OffsetDateTime start, OffsetDateTime end) {
    }
}
