package com.bikeprojectminji.bikeback.achievement.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AchievementCompletionDispatcher {

    private final AchievementCompletionExecutor achievementCompletionExecutor;

    public AchievementCompletionDispatcher(AchievementCompletionExecutor achievementCompletionExecutor) {
        this.achievementCompletionExecutor = achievementCompletionExecutor;
    }

    public void dispatchAfterCommit(AchievementCompletionSignal signal) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            achievementCompletionExecutor.grantForCompletedCourse(signal);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                achievementCompletionExecutor.grantForCompletedCourse(signal);
            }
        });
    }
}
