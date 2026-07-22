package com.bikeprojectminji.bikeback.party.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.util.ErrorHandler;

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

    @Test
    void disabledAutoStartupKeepsScheduledRecoveryInertUntilExplicitStart() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        RidePartyRedisPubSubEventBus bus = busWithAutoStartupDisabled(container);

        bus.recoverIfNecessary();

        verify(container, never()).start();
        verify(container, never()).addMessageListener(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection());
        assertThat(bus.isRunning()).isFalse();
    }

    @Test
    void explicitStartEnablesRecoveryAndStopDisablesItAgain() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        RidePartyRedisPubSubEventBus bus = busWithAutoStartupDisabled(container);
        when(container.isRunning()).thenReturn(true);

        bus.start();
        verify(container).addMessageListener(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection());
        assertThat(bus.isRunning()).isTrue();

        ArgumentCaptor<ErrorHandler> errorHandler = ArgumentCaptor.forClass(ErrorHandler.class);
        verify(container).setErrorHandler(errorHandler.capture());
        errorHandler.getValue().handleError(new RuntimeException("subscription unavailable"));
        assertThat(bus.isRunning()).isFalse();
        bus.recoverIfNecessary();
        verify(container, org.mockito.Mockito.times(2)).addMessageListener(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection());

        bus.stop();
        bus.recoverIfNecessary();
        verify(container, org.mockito.Mockito.times(2)).addMessageListener(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyCollection());
    }

    private RidePartyRedisPubSubEventBus busWithAutoStartupDisabled(RedisMessageListenerContainer container) {
        return new RidePartyRedisPubSubEventBus(mock(StringRedisTemplate.class), container, new ObjectMapper(),
                raw -> { }, operation -> { }, () -> { }, () -> { }, () -> { }, false);
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
