package com.bikeprojectminji.bikeback.achievement.repository;

import com.bikeprojectminji.bikeback.achievement.entity.AchievementGrantEntity;
import com.bikeprojectminji.bikeback.achievement.entity.AchievementType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AchievementGrantRepository extends JpaRepository<AchievementGrantEntity, Long> {

    boolean existsByUserIdAndAchievementTypeAndSourceKey(Long userId, AchievementType achievementType, String sourceKey);

    List<AchievementGrantEntity> findByUserIdOrderByGrantedAtDescIdDesc(Long userId);

    List<AchievementGrantEntity> findByUserIdAndAchievementTypeOrderBySourceKeyAsc(Long userId, AchievementType achievementType);
}
