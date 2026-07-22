package com.bikeprojectminji.bikeback.party.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.party.service.RidePartyLocationAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.LifecycleProcessor;
import org.springframework.context.support.AbstractApplicationContext;

class RidePartyRedisPubSubWebSocketConfigurationTest {

    @Test
    void productionConfigurationBuildsListenerContainerFromRedisConnectionFactory() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);

        RedisMessageListenerContainer container = new RidePartyRedisPubSubConfiguration()
                .ridePartyRedisMessageListenerContainer(connectionFactory);

        assertThat(container).isNotNull();
        assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
    }

    @Test
    void lifecycleOwnedSweepInvokesAuthoritativeMembershipRevalidation() {
        RidePartyDistributedStateService stateService = mock(RidePartyDistributedStateService.class);

        new RidePartySocketAccessSweep(stateService).sweep();

        verify(stateService).revalidateAllSessionsOrDrain();
    }

    @Test
    void springContextOwnsNamedPartyContainerAlongsideAnUnrelatedSharedContainer() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ContextConfig.class)) {
            RedisMessageListenerContainer party = context.getBean("ridePartyRedisMessageListenerContainer", RedisMessageListenerContainer.class);
            RedisMessageListenerContainer shared = context.getBean("sharedRedisMessageListenerContainer", RedisMessageListenerContainer.class);

            assertThat(party).isNotSameAs(shared);
            assertThat(party.getConnectionFactory()).isSameAs(context.getBean(RedisConnectionFactory.class));
        }
    }
    @Test
    void springProductionGraphResolvesDedicatedBusStateAndSweep() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ProductionGraphConfig.class)) {
            assertThat(context.getBean(RidePartyRedisPubSubEventBus.class)).isNotNull();
            assertThat(context.getBean(RidePartyDistributedStateService.class)).isNotNull();
            assertThat(context.getBean(RidePartySocketAccessSweep.class)).isNotNull();
            assertThat(context.getBean("ridePartyRedisMessageListenerContainer", RedisMessageListenerContainer.class))
                    .isNotSameAs(context.getBean("sharedRedisMessageListenerContainer", RedisMessageListenerContainer.class));
        }
    }

    @Configuration
    @Import(RidePartyRedisPubSubConfiguration.class)
    static class ContextConfig {
        @Bean RedisConnectionFactory connectionFactory() { return mock(RedisConnectionFactory.class); }
        @Bean(name = "sharedRedisMessageListenerContainer") RedisMessageListenerContainer sharedContainer() { return mock(RedisMessageListenerContainer.class); }
        @Bean(name = AbstractApplicationContext.LIFECYCLE_PROCESSOR_BEAN_NAME)
        LifecycleProcessor noOpLifecycleProcessor() {
            return new LifecycleProcessor() {
                public void onRefresh() { }
                public void onClose() { }
                public void start() { }
                public void stop() { }
                public boolean isRunning() { return false; }
            };
        }
    }
    @Configuration
    @Import({
            ContextConfig.class,
            RidePartyRedisPubSubEventBus.class,
            RidePartyDistributedStateService.class,
            RidePartySocketAccessSweep.class,
            RidePartySocketSessionRegistry.class
    })
    static class ProductionGraphConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate() {
            return mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        }
        @Bean RidePartyLocationAccessService ridePartyLocationAccessService() {
            return mock(RidePartyLocationAccessService.class);
        }
    }
}
