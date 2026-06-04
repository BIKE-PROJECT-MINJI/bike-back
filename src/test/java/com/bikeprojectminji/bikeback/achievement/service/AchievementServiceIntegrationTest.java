package com.bikeprojectminji.bikeback.achievement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.achievement.entity.AchievementGrantEntity;
import com.bikeprojectminji.bikeback.achievement.entity.AchievementType;
import com.bikeprojectminji.bikeback.achievement.repository.AchievementGrantRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AchievementServiceIntegrationTest {

    @Autowired
    private AchievementService achievementService;

    @Autowired
    private AchievementGrantRepository achievementGrantRepository;

    @BeforeEach
    void setUp() {
        achievementGrantRepository.deleteAll();
    }

    @Test
    @DisplayName("완료된 한강권 코스는 MVP 3종 성취를 한 번씩 지급한다")
    void grantForCompletedCourseGrantsMvpAchievementsOnce() {
        AchievementCompletionSignal signal = hanRiverCompletionSignal(1L, 10L, 20L);

        achievementService.grantForCompletedCourse(signal);
        achievementService.grantForCompletedCourse(signal);

        List<AchievementGrantEntity> grants = achievementGrantRepository.findByUserIdOrderByGrantedAtDescIdDesc(1L);
        assertThat(grants).hasSize(3);
        assertThat(grants)
                .extracting(AchievementGrantEntity::getAchievementType)
                .containsExactlyInAnyOrder(
                        AchievementType.FIRST_COURSE_COMPLETION,
                        AchievementType.RIVERSIDE_OR_BIKE_ROAD_COMPLETION,
                        AchievementType.NEW_AREA_VISIT
                );
        assertThat(grants)
                .filteredOn(grant -> grant.getAchievementType() == AchievementType.NEW_AREA_VISIT)
                .singleElement()
                .extracting(AchievementGrantEntity::getSourceKey)
                .isEqualTo("SEOUL_JUNG_GU");
    }

    @Test
    @DisplayName("알 수 없는 지역의 완료 코스는 새 지역 방문 성취를 지급하지 않는다")
    void grantForCompletedCourseDoesNotGrantNewAreaForUnknownArea() {
        AchievementCompletionSignal signal = new AchievementCompletionSignal(
                1L,
                10L,
                20L,
                List.of(new AchievementRoutePoint(BigDecimal.valueOf(35.1000), BigDecimal.valueOf(129.0300)))
        );

        achievementService.grantForCompletedCourse(signal);

        assertThat(achievementGrantRepository.findByUserIdOrderByGrantedAtDescIdDesc(1L))
                .extracting(AchievementGrantEntity::getAchievementType)
                .doesNotContain(AchievementType.NEW_AREA_VISIT);
    }

    @Test
    @DisplayName("서로 다른 지역 방문은 지역 sourceKey 기준으로 각각 지급된다")
    void grantForCompletedCourseGrantsNewAreaPerAreaSourceKey() {
        achievementService.grantForCompletedCourse(hanRiverCompletionSignal(1L, 10L, 20L));
        achievementService.grantForCompletedCourse(new AchievementCompletionSignal(
                1L,
                11L,
                21L,
                List.of(new AchievementRoutePoint(BigDecimal.valueOf(37.6510), BigDecimal.valueOf(127.0560)))
        ));

        assertThat(achievementGrantRepository.findByUserIdAndAchievementTypeOrderBySourceKeyAsc(1L, AchievementType.NEW_AREA_VISIT))
                .extracting(AchievementGrantEntity::getSourceKey)
                .containsExactly("SEOUL_JUNG_GU", "SEOUL_NOWON_GU");
    }

    private AchievementCompletionSignal hanRiverCompletionSignal(Long userId, Long courseId, Long rideRecordId) {
        return new AchievementCompletionSignal(
                userId,
                courseId,
                rideRecordId,
                List.of(
                        new AchievementRoutePoint(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new AchievementRoutePoint(BigDecimal.valueOf(37.5650), BigDecimal.valueOf(126.9820))
                )
        );
    }
}
