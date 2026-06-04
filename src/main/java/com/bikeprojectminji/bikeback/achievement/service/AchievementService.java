package com.bikeprojectminji.bikeback.achievement.service;

import com.bikeprojectminji.bikeback.achievement.dto.AchievementItemResponse;
import com.bikeprojectminji.bikeback.achievement.dto.AchievementListResponse;
import com.bikeprojectminji.bikeback.achievement.entity.AchievementGrantEntity;
import com.bikeprojectminji.bikeback.achievement.entity.AchievementType;
import com.bikeprojectminji.bikeback.achievement.repository.AchievementGrantRepository;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AchievementService {

    private static final String GLOBAL_SOURCE_KEY = "global";

    private final AchievementGrantRepository achievementGrantRepository;
    private final AuthService authService;
    private final Clock clock;
    private final AchievementRouteClassifier routeClassifier = new AchievementRouteClassifier();
    private final AchievementAreaResolver areaResolver = new AchievementAreaResolver();

    public AchievementService(
            AchievementGrantRepository achievementGrantRepository,
            AuthService authService,
            Clock clock
    ) {
        this.achievementGrantRepository = achievementGrantRepository;
        this.authService = authService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AchievementListResponse getMyAchievements(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        List<AchievementItemResponse> achievements = achievementGrantRepository.findByUserIdOrderByGrantedAtDescIdDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
        return new AchievementListResponse(achievements);
    }

    @Transactional
    public void grantForCompletedCourse(AchievementCompletionSignal signal) {
        if (signal == null || signal.userId() == null || signal.courseId() == null || signal.rideRecordId() == null) {
            return;
        }
        List<GrantCandidate> candidates = new ArrayList<>();
        candidates.add(new GrantCandidate(AchievementType.FIRST_COURSE_COMPLETION, GLOBAL_SOURCE_KEY));
        if (routeClassifier.isRiversideOrBikeRoad(signal.routePoints())) {
            candidates.add(new GrantCandidate(AchievementType.RIVERSIDE_OR_BIKE_ROAD_COMPLETION, GLOBAL_SOURCE_KEY));
        }
        areaResolver.resolveFirstAreaCode(signal.routePoints())
                .ifPresent(areaCode -> candidates.add(new GrantCandidate(AchievementType.NEW_AREA_VISIT, areaCode)));

        for (GrantCandidate candidate : candidates) {
            grantIfMissing(signal, candidate);
        }
    }

    private void grantIfMissing(AchievementCompletionSignal signal, GrantCandidate candidate) {
        if (achievementGrantRepository.existsByUserIdAndAchievementTypeAndSourceKey(signal.userId(), candidate.type(), candidate.sourceKey())) {
            return;
        }
        achievementGrantRepository.save(AchievementGrantEntity.create(
                signal.userId(),
                candidate.type(),
                candidate.sourceKey(),
                signal.courseId(),
                signal.rideRecordId(),
                clock
        ));
    }

    private AchievementItemResponse toResponse(AchievementGrantEntity entity) {
        return new AchievementItemResponse(
                entity.getAchievementType(),
                titleOf(entity.getAchievementType()),
                descriptionOf(entity.getAchievementType()),
                entity.getSourceKey(),
                entity.getSourceCourseId(),
                entity.getSourceRideRecordId(),
                entity.getGrantedAt()
        );
    }

    private String titleOf(AchievementType type) {
        return switch (type) {
            case FIRST_COURSE_COMPLETION -> "첫 코스 완주";
            case RIVERSIDE_OR_BIKE_ROAD_COMPLETION -> "강변/자전거길 코스 완주";
            case NEW_AREA_VISIT -> "새 지역 방문";
        };
    }

    private String descriptionOf(AchievementType type) {
        return switch (type) {
            case FIRST_COURSE_COMPLETION -> "첫 코스를 완주했습니다.";
            case RIVERSIDE_OR_BIKE_ROAD_COMPLETION -> "강변 또는 자전거길 코스를 완주했습니다.";
            case NEW_AREA_VISIT -> "새로운 지역을 방문했습니다.";
        };
    }

    private record GrantCandidate(AchievementType type, String sourceKey) {
    }
}
