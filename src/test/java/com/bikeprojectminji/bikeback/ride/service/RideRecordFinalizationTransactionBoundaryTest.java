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
    @DisplayName("자유 주행 최종 처리는 별도 processor 빈에서 트랜잭션 프록시로 실행된다")
    void finalizationProcessorRunsBehindTransactionalProxy() {
        Object processor = applicationContext.getBean("rideRecordFinalizationProcessor");

        assertThat(processor).isNotNull();
        assertThat(AopUtils.isAopProxy(processor)).isTrue();
    }
}
