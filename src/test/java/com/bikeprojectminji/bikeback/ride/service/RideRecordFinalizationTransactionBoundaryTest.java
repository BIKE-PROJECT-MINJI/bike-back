package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RideRecordFinalizationTransactionBoundaryTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("자유 주행 최종 경로 교체는 writer 빈에서 트랜잭션 프록시로 실행된다")
    void finalizationWriterRunsBehindTransactionalProxy() {
        Object processor = applicationContext.getBean("rideRecordFinalizationWriter");

        assertThat(processor).isNotNull();
        assertThat(AopUtils.isAopProxy(processor)).isTrue();
    }

    @Test
    @DisplayName("자유 주행 최종 처리 실패 상태는 별도 트랜잭션 프록시로 기록된다")
    void finalizationFailureServiceRunsBehindTransactionalProxy() {
        Object failureService = applicationContext.getBean("rideRecordFinalizationFailureService");

        assertThat(failureService).isNotNull();
        assertThat(AopUtils.isAopProxy(failureService)).isTrue();
    }
}
