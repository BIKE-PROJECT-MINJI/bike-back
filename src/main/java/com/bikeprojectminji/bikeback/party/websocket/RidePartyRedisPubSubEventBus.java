package com.bikeprojectminji.bikeback.party.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class RidePartyRedisPubSubEventBus implements RidePartyDistributedEventPublisher, MessageListener, SmartLifecycle {

    public static final String TOPIC = "bike:party:websocket:v1";
    public static final String HEALTH_TOPIC = TOPIC + ":health";
    private static final long HEARTBEAT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final Logger log = LoggerFactory.getLogger(RidePartyRedisPubSubEventBus.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;
    private final Consumer<String> inboundMessageConsumer;
    private final Consumer<String> failureConsumer;
    private final Runnable recoveringConsumer;
    private final Runnable readyConsumer;
    private final Runnable stoppedConsumer;
    private final LongSupplier nanoTime;
    private final long heartbeatTimeoutNanos;
    private final boolean autoStartup;
    private volatile boolean running;
    private volatile long generation;
    private volatile long confirmedGeneration = -1;
    private volatile long readyGeneration = -1;
    private volatile long subscriptionStartedAtNanos;
    private volatile long lastHeartbeatEchoAtNanos;
    private volatile String pendingHeartbeatNonce;
    private volatile long pendingHeartbeatSentAtNanos;
    private volatile MessageListener activeListener;
    private volatile boolean listenerRegistered;
    private volatile boolean recoveryAllowed = true;

    @Autowired
    public RidePartyRedisPubSubEventBus(
            StringRedisTemplate redisTemplate,
            @Qualifier("ridePartyRedisMessageListenerContainer") RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper,
            ObjectProvider<RidePartyDistributedStateService> distributedStateService,
            @Value("${bike.party.redis.auto-start:true}") boolean autoStartup
    ) {
        this(redisTemplate, listenerContainer, objectMapper,
                rawMessage -> distributedStateService.getObject().receive(rawMessage),
                operation -> distributedStateService.getObject().onBusFailure(operation),
                () -> distributedStateService.getObject().onSubscriptionStarting(),
                () -> distributedStateService.getObject().onSubscriptionConfirmed(),
                () -> distributedStateService.getObject().onBusStopped(), autoStartup);
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
            ObjectMapper objectMapper, Consumer<String> inboundMessageConsumer, Consumer<String> failureConsumer,
            Runnable recoveringConsumer, Runnable readyConsumer, Runnable stoppedConsumer) {
        this(redisTemplate, listenerContainer, objectMapper, inboundMessageConsumer, failureConsumer,
                recoveringConsumer, readyConsumer, stoppedConsumer, System::nanoTime, HEARTBEAT_TIMEOUT_NANOS);
    }

    RidePartyRedisPubSubEventBus(StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper, Consumer<String> inboundMessageConsumer, Consumer<String> failureConsumer,
            Runnable recoveringConsumer, Runnable readyConsumer, Runnable stoppedConsumer, boolean autoStartup) {
        this(redisTemplate, listenerContainer, objectMapper, inboundMessageConsumer, failureConsumer,
                recoveringConsumer, readyConsumer, stoppedConsumer, System::nanoTime, HEARTBEAT_TIMEOUT_NANOS, autoStartup);
    }

    RidePartyRedisPubSubEventBus(StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper, Consumer<String> inboundMessageConsumer, Consumer<String> failureConsumer,
            Runnable recoveringConsumer, Runnable readyConsumer, Runnable stoppedConsumer,
            LongSupplier nanoTime, long heartbeatTimeoutNanos) {
        this(redisTemplate, listenerContainer, objectMapper, inboundMessageConsumer, failureConsumer,
                recoveringConsumer, readyConsumer, stoppedConsumer, nanoTime, heartbeatTimeoutNanos, true);
    }

    RidePartyRedisPubSubEventBus(StringRedisTemplate redisTemplate, RedisMessageListenerContainer listenerContainer,
            ObjectMapper objectMapper, Consumer<String> inboundMessageConsumer, Consumer<String> failureConsumer,
            Runnable recoveringConsumer, Runnable readyConsumer, Runnable stoppedConsumer,
            LongSupplier nanoTime, long heartbeatTimeoutNanos, boolean autoStartup) {
        this.redisTemplate = redisTemplate;
        this.listenerContainer = listenerContainer;
        this.objectMapper = objectMapper;
        this.inboundMessageConsumer = inboundMessageConsumer;
        this.failureConsumer = failureConsumer;
        this.recoveringConsumer = recoveringConsumer;
        this.readyConsumer = readyConsumer;
        this.stoppedConsumer = stoppedConsumer;
        this.nanoTime = nanoTime;
        this.heartbeatTimeoutNanos = heartbeatTimeoutNanos;
        this.autoStartup = autoStartup;
        this.listenerContainer.setErrorHandler(error -> fail("listener"));
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
        pendingHeartbeatNonce = null;
        running = false;
        generation++;
        try {
            if (removeListener) {
                listenerContainer.removeMessageListener(listenerToRemove, topics());
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
            confirmedGeneration = -1;
            readyGeneration = -1;
            subscriptionStartedAtNanos = nanoTime.getAsLong();
            lastHeartbeatEchoAtNanos = 0;
            pendingHeartbeatNonce = null;
            pendingHeartbeatSentAtNanos = 0;
            activeListener = new SubscriptionAwareListener(registeredGeneration);
            listenerRegistered = true;
            running = true;
            listenerContainer.addMessageListener(activeListener, topics());
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
        return autoStartup;
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
        long now = nanoTime.getAsLong();
        if ((pendingHeartbeatNonce != null && now - pendingHeartbeatSentAtNanos >= heartbeatTimeoutNanos)
                || (lastHeartbeatEchoAtNanos != 0 && now - lastHeartbeatEchoAtNanos >= heartbeatTimeoutNanos)) {
            fail("heartbeat");
            return;
        }
        try {
            String response = redisTemplate.execute(
                    (org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
            if (!"PONG".equalsIgnoreCase(response)) {
                throw new IllegalStateException("Redis ping did not return PONG.");
            }
        } catch (RuntimeException exception) {
            fail("ping");
            return;
        }
        if (pendingHeartbeatNonce != null) {
            return;
        }
        String nonce = UUID.randomUUID().toString();
        pendingHeartbeatNonce = nonce;
        pendingHeartbeatSentAtNanos = now;
        try {
            Long recipients = redisTemplate.convertAndSend(HEALTH_TOPIC, nonce);
            if (recipients == null || recipients <= 0) {
                throw new IllegalStateException("Redis Pub/Sub health channel has no active recipients.");
            }
        } catch (RuntimeException exception) {
            pendingHeartbeatNonce = null;
            fail("heartbeat-publish");
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        receive(generation, message);
    }

    private synchronized void subscriptionConfirmed(long callbackGeneration) {
        if (!isCurrentGeneration(callbackGeneration)) {
            return;
        }
        confirmedGeneration = callbackGeneration;
        completeReadiness(callbackGeneration);
    }

    private synchronized void receive(long callbackGeneration, Message message) {
        if (!isCurrentGeneration(callbackGeneration)) {
            return;
        }
        if (Arrays.equals(message.getChannel(), HEALTH_TOPIC.getBytes(StandardCharsets.UTF_8))) {
            String receivedNonce = new String(message.getBody(), StandardCharsets.UTF_8);
            if (receivedNonce.equals(pendingHeartbeatNonce)) {
                pendingHeartbeatNonce = null;
                lastHeartbeatEchoAtNanos = nanoTime.getAsLong();
                completeReadiness(callbackGeneration);
            }
            return;
        }
        try {
            inboundMessageConsumer.accept(new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            if (isCurrentGeneration(callbackGeneration)) {
                fail("listener");
            }
        }
    }

    private boolean isCurrentGeneration(long callbackGeneration) {
        return running && callbackGeneration == generation;
    }

    private void completeReadiness(long callbackGeneration) {
        if (confirmedGeneration == callbackGeneration && lastHeartbeatEchoAtNanos >= subscriptionStartedAtNanos
                && readyGeneration != callbackGeneration) {
            readyGeneration = callbackGeneration;
            readyConsumer.run();
        }
    }

    private List<ChannelTopic> topics() {
        return List.of(new ChannelTopic(TOPIC), new ChannelTopic(HEALTH_TOPIC));
    }

    private final class SubscriptionAwareListener implements MessageListener, SubscriptionListener {
        private final long callbackGeneration;

        private SubscriptionAwareListener(long callbackGeneration) {
            this.callbackGeneration = callbackGeneration;
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            receive(callbackGeneration, message);
        }

        @Override
        public void onChannelSubscribed(byte[] channel, long count) {
            if (Arrays.equals(channel, TOPIC.getBytes(StandardCharsets.UTF_8))) {
                subscriptionConfirmed(callbackGeneration);
            }
        }
    }

    private synchronized void fail(String operation) {
        MessageListener failedListener = activeListener;
        listenerRegistered = false;
        activeListener = null;
        pendingHeartbeatNonce = null;
        running = false;
        generation++;
        if (failedListener != null) {
            try {
                listenerContainer.removeMessageListener(failedListener, topics());
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
