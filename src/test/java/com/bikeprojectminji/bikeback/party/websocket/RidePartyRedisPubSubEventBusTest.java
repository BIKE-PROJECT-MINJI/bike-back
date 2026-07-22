package com.bikeprojectminji.bikeback.party.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.support.TestPropertySourceUtils;

class RidePartyRedisPubSubEventBusTest {

    @Test
    void defaultsToAutoStartupForProductionContexts() {
        try (AnnotationConfigApplicationContext context = contextWith()) {
            assertThat(context.getBean(RidePartyRedisPubSubEventBus.class).isAutoStartup()).isTrue();
        }
    }

    @Test
    void respectsConfiguredAutoStartupSettingForTestContexts() {
        try (AnnotationConfigApplicationContext context = contextWith("bike.party.redis.auto-start=false")) {
            assertThat(context.getBean(RidePartyRedisPubSubEventBus.class).isAutoStartup()).isFalse();
        }
    }

    private AnnotationConfigApplicationContext contextWith(String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.registerBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));
        context.registerBean("ridePartyRedisMessageListenerContainer", RedisMessageListenerContainer.class,
                () -> mock(RedisMessageListenerContainer.class));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(RidePartyDistributedStateService.class, () -> mock(RidePartyDistributedStateService.class));
        context.register(RidePartyRedisPubSubEventBus.class);
        context.refresh();
        return context;
    }
}
