package com.bikeprojectminji.bikeback.party.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bikeprojectminji.bikeback.party.event.RidePartyCanceledEvent;
import com.bikeprojectminji.bikeback.party.event.RidePartyMemberLeftEvent;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RidePartySocketRevocationListenerTest.TestConfig.class)
class RidePartySocketRevocationListenerTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private RidePartySocketSessionRegistry sessionRegistry;

    @AfterEach
    void resetRegistry() {
        reset(sessionRegistry);
    }

    @Test
    @DisplayName("멤버 소켓은 트랜잭션 커밋 전에는 닫지 않고 AFTER_COMMIT에 닫는다")
    void closesMemberOnlyAfterCommit() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new RidePartyMemberLeftEvent(20L, 2L));
            verifyNoInteractions(sessionRegistry);
        });

        verify(sessionRegistry).closeMember(20L, 2L);
    }

    @Test
    @DisplayName("파티 소켓은 트랜잭션 커밋 전에는 닫지 않고 AFTER_COMMIT에 닫는다")
    void closesPartyOnlyAfterCommit() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new RidePartyCanceledEvent(20L));
            verifyNoInteractions(sessionRegistry);
        });

        verify(sessionRegistry).closeParty(20L);
    }

    @Test
    @DisplayName("트랜잭션 롤백 시 소켓을 닫지 않는다")
    void doesNotCloseSocketAfterRollback() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            eventPublisher.publishEvent(new RidePartyMemberLeftEvent(20L, 2L));
            status.setRollbackOnly();
        });

        verifyNoInteractions(sessionRegistry);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        RidePartySocketSessionRegistry sessionRegistry() {
            return mock(RidePartySocketSessionRegistry.class);
        }

        @Bean
        RidePartySocketRevocationListener listener(RidePartySocketSessionRegistry registry) {
            return new RidePartySocketRevocationListener(registry);
        }
    }
}
