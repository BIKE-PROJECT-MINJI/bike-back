package com.bikeprojectminji.bikeback.achievement.service;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AchievementCompletionDispatcherTest {

    @Mock
    private AchievementCompletionExecutor achievementCompletionExecutor;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("활성 트랜잭션이 없으면 완료 코스 업적을 즉시 지급한다")
    void dispatchAfterCommitGrantsImmediatelyWithoutActiveTransaction() {
        AchievementCompletionSignal signal = signal();

        dispatcher().dispatchAfterCommit(signal);

        then(achievementCompletionExecutor).should().grantForCompletedCourse(signal);
    }

    @Test
    @DisplayName("활성 트랜잭션이 있으면 commit 이후에만 완료 코스 업적을 지급한다")
    void dispatchAfterCommitDefersGrantUntilCommit() {
        AchievementCompletionSignal signal = signal();
        TransactionSynchronizationManager.initSynchronization();

        dispatcher().dispatchAfterCommit(signal);

        then(achievementCompletionExecutor).should(never()).grantForCompletedCourse(signal);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.get(0).afterCommit();
        then(achievementCompletionExecutor).should().grantForCompletedCourse(signal);
    }

    @Test
    @DisplayName("활성 트랜잭션이 rollback되면 완료 코스 업적을 지급하지 않는다")
    void dispatchAfterCommitDoesNotGrantAfterRollback() {
        AchievementCompletionSignal signal = signal();
        TransactionSynchronizationManager.initSynchronization();

        dispatcher().dispatchAfterCommit(signal);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        then(achievementCompletionExecutor).should(never()).grantForCompletedCourse(signal);
    }

    private AchievementCompletionDispatcher dispatcher() {
        return new AchievementCompletionDispatcher(achievementCompletionExecutor);
    }

    private AchievementCompletionSignal signal() {
        return new AchievementCompletionSignal(
                1L,
                10L,
                20L,
                List.of(new AchievementRoutePoint(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)))
        );
    }
}
