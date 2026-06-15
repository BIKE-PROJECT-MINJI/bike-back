package com.bikeprojectminji.bikeback.achievement.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AchievementCompletionExecutor {

    private final AchievementService achievementService;

    public AchievementCompletionExecutor(AchievementService achievementService) {
        this.achievementService = achievementService;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void grantForCompletedCourse(AchievementCompletionSignal signal) {
        achievementService.grantForCompletedCourse(signal);
    }
}
