package com.bikeprojectminji.bikeback.party.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class RidePartyRedisPubSubEventBus implements RidePartyDistributedEventPublisher, MessageListener, SmartLifecycle {

    public static final String TOPIC = "bike:party:websocket:v1";
    private static final Logger log = LoggerFactory.getLogger(RidePartyRedisPubSubEventBus.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final Consumer<String> inboundMessageConsumer;
    private final Consumer<String> failureConsumer;
    private final Runnable recoveringConsumer;
    private final Runnable readyConsumer;
    private final Runnable stoppedConsumer;
    private volatile boolean running;
    private volatile long generation;
    private volatile MessageListener activeListener;
    private volatile boolean listenerRegistered;
    private volatile boolean recoveryAllowed = true;

    public RidePartyRedisPubSubEventBus(
            StringRedisTemplate redisTemplate,
            @Qualifier("ridePartyRedisMessageListenerContainer") RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            ObjectProvider<RidePartyDistributedStateService> distributedStateService
    ) {
        this(redisTemplate, listenerContainer, objectMapper,
                rawMessage -> distributedStateService.getObject().receive(rawMessage),
                operation -> distributedStateService.getObject().onBusFailure(operation),
                () -> distributedStateService.getObject().onSubscriptionStarting(),
                () -> distributedStateService.getObject().onSubscriptionConfirmed(),
                () -> distributedStateService.getObject().onBusStopped());
    }

    RidePartyRedisPubSubEventBus(
            StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            Consumer<String> inboundMessageConsumer
    ) {
        this(redisTemplate, listenerContainer, objectMapper, inboundMessageConsumer, ignored -> { }, () -> { }, () -> { }, () -> { });
    }

    RidePartyRedisPubSubEventBus(
            StringRedisTemplate redisTemplate,
            RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            Consumer<String> inboundMessageConsumer,
            Consumer<String> failureConsumer
    ) {
        this(redisTemplate, listenerContainer, objectMapper, inboundMessageConsumer, failureConsumer, () -> { }, () -> { }, () -> { });
    }

    RidePartyRedisPubSubEventBus(StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper, Consumer<String> inboundMessageConsumer, Consumer<String> failureConsumer, Runnable readyConsumer) {
        this(redisTemplate, listenerContainer, objectMapper, inboundMessageConsumer, failureConsumer, () -> { }, readyConsumer, () -> { });
    }

    RidePartyRedisPubSubEventBus(StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper, Consumer<String> inboundMessageConsumer, Consumer<String> failureConsumer, Runnable recoveringConsumer, Runnable readyConsumer, Runnable stoppedConsumer) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.inboundMessageConsumer = inboundMessageConsumer;
        this.failureConsumer = failureConsumer;
        this.recoveringConsumer = recoveringConsumer;
        this.readyConsumer = readyConsumer;
        this.stoppedConsumer = stoppedConsumer;
    }

    @Override
    public void publish(RidePartyDistributedEvent event) {
        try {
            Long recipients = redisTemplate.convertAndSend(TOPIC, objectMapper.writeValueAsString(event));
            if (recipients == null || recipients <= 0) throw new IllegalStateException("Redis Pub/Sub has no active recipients.");
        } catch (Exception exception) {
            log.warn("party websocket redis publish failed eventType={} eventId={}", event.eventType(), event.eventId(), exception);
            throw new RidePartyDistributedPublishException(event.eventType().name(), exception);
        }
    }

    @Override
    public synchronized void start() {
        recoveryAllowed = true;
        startSubscription();
    }

    @Override
    public synchronized void stop() {
        recoveryAllowed = false;
        MessageListener listenerToRemove = activeListener;
        boolean removeListener = listenerRegistered;
        listenerRegistered = false;
        activeListener = null;
        running = false;
        generation++;
        try {
            if (removeListener) {
                listenerContainer.removeMessageListener(listenerToRemove, new ChannelTopic(TOPIC));
            }
        } catch (RuntimeException exception) {
            log.warn("party websocket redis listener removal failed during stop", exception);
        } finally {
            try {
                stoppedConsumer.run();
            } catch (RuntimeException exception) {
                log.warn("party websocket stop drain callback failed", exception);
            }
        }
    }

    private synchronized void startSubscription() {
        if (running) {
            return;
        }
        try {
            if (!listenerContainer.isRunning()) {
                listenerContainer.start();
            }
            long registeredGeneration = ++generation;
            activeListener = (message, pattern) -> receive(registeredGeneration, message);
            listenerContainer.addMessageListener(activeListener, new ChannelTopic(TOPIC));
            listenerRegistered = true;
            running = true;
            recoveringConsumer.run();
        } catch (RuntimeException exception) {
            fail("subscribe");
            throw new RidePartyDistributedSubscriptionException(exception);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Scheduled(fixedDelay = 5_000)
    synchronized void recoverIfNecessary() {
        if (!running && recoveryAllowed) {
            try {
                startSubscription();
            } catch (RidePartyDistributedSubscriptionException ignored) {
                // Remain fail-closed; the next lifecycle-owned retry will try a fresh generation.
            }
        }
    }

    @Scheduled(fixedDelay = 1_000)
    synchronized void confirmSubscriptionOrFail() {
        if (!running) return;
        if (!listenerContainer.isRunning() || !listenerContainer.isListening()) {
            fail("disconnect");
            return;
        }
        readyConsumer.run();
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        receive(generation, message);
    }
    private void receive(long callbackGeneration, Message message) {
        if (!running || callbackGeneration != generation) {
            return;
        }
        try {
            inboundMessageConsumer.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            fail("listener");
        }
    }

    private synchronized void fail(String operation) {
        MessageListener failedListener = activeListener;
        listenerRegistered = false;
        activeListener = null;
        running = false;
        generation++;
        if (failedListener != null) {
            try {
                listenerContainer.removeMessageListener(failedListener, new ChannelTopic(TOPIC));
            } catch (RuntimeException ignored) {
                // Redis may already have discarded the failed registration.
            }
        }
        try {
            failureConsumer.accept(operation);
        } catch (RuntimeException exception) {
            log.warn("party websocket failure drain callback failed operation={}", operation, exception);
        }
        log.warn("party websocket redis listener failure operation={}", operation);
    }
}
