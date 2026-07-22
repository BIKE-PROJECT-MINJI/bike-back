package com.bikeprojectminji.bikeback.party.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bikeprojectminji.bikeback.party.service.RidePartyLocationAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

class RidePartyDistributedWebSocketTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.systemUTC();

    @Test
    @DisplayName("두 논리 노드는 Redis bus로 party 범위의 location만 fan-out한다")
    void fansOutLocationToRemoteNodeWithoutCrossPartyLeak() throws Exception {
        FakeBus bus = new FakeBus();
        Node nodeA = node("node-a", bus);
        Node nodeB = node("node-b", bus);
        WebSocketSession eligible = openSession();
        WebSocketSession differentParty = openSession();
        nodeB.registry.register(20L, 3L, eligible);
        nodeB.registry.register(21L, 4L, differentParty);
        when(nodeB.access.canShare(20L, 2L)).thenReturn(true);
        when(nodeB.access.canShare(20L, 3L)).thenReturn(true);

        nodeA.service.publishLocation(20L, 2L, locationPayload());

        verify(eligible).sendMessage(argThat(message -> ((TextMessage) message).getPayload().contains("\"type\":\"location\"")));
        verify(differentParty, never()).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("self echo와 duplicate eventId는 remote WebSocket에 재적용하지 않는다")
    void ignoresSelfEchoAndDuplicateEvent() throws Exception {
        FakeBus bus = new FakeBus();
        Node nodeA = node("node-a", bus);
        Node nodeB = node("node-b", bus);
        WebSocketSession own = openSession();
        WebSocketSession remote = openSession();
        nodeA.registry.register(20L, 2L, own);
        nodeB.registry.register(20L, 3L, remote);
        when(nodeA.access.canShare(20L, 2L)).thenReturn(true);
        when(nodeB.access.canShare(20L, 2L)).thenReturn(true);
        when(nodeB.access.canShare(20L, 3L)).thenReturn(true);
        bus.duplicateNextDelivery = true;

        nodeA.service.publishLocation(20L, 2L, locationPayload());

        verify(own, never()).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(remote).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(remote, org.mockito.Mockito.times(1)).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("잘못된 envelope는 적용하지 않고 측정 가능한 receive failure를 남긴다")
    void rejectsMalformedEnvelope() {
        Node node = node("node-b", new FakeBus());
        WebSocketSession session = openSession();
        node.registry.register(20L, 2L, session);

        node.service.receive("{\"version\":1,\"eventId\":\"x\"}");

        assertThat(node.service.status().subscriberHealthy()).isFalse();
        assertThat(node.service.subscriberState()).isEqualTo(RidePartySubscriberState.DEGRADED);
        try { verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("distributed-bus-unavailable")); } catch (Exception exception) { throw new AssertionError(exception); }
    }

    @Test
    @DisplayName("unknown field와 forged location identity envelope는 거부한다")
    void rejectsUnknownFieldAndForgedIdentity() throws Exception {
        Node node = node("node-b", new FakeBus());
        String unknown = """
                {"version":1,"eventId":"f47ac10b-58cc-4372-a567-0e02b2c3d479","sourceNodeId":"node-a",
                "eventType":"LOCATION","partyId":20,"userId":2,"payload":{},"emittedAt":"2026-07-22T07:32:00Z","extra":true}
                """;
        node.service.receive(unknown);

        assertThat(node.service.status().healthy()).isFalse();
    }

    @Test
    @DisplayName("원격 revoke와 cancel은 정책 위반 reason으로 해당 노드 세션을 닫는다")
    void closesRemoteSessionsForRevokeAndCancel() throws Exception {
        FakeBus bus = new FakeBus();
        Node nodeA = node("node-a", bus);
        Node nodeB = node("node-b", bus);
        WebSocketSession revoked = openSession();
        WebSocketSession canceled = openSession();
        nodeB.registry.register(20L, 2L, revoked);
        nodeB.registry.register(20L, 3L, canceled);
        when(nodeB.access.canShare(20L, 2L)).thenReturn(false);
        when(nodeB.access.canShare(20L, 3L)).thenReturn(false);

        nodeA.service.publishMemberRevoked(20L, 2L);
        nodeA.service.publishPartyCanceled(20L);

        verify(revoked).close(CloseStatus.POLICY_VIOLATION.withReason("member-left"));
        verify(canceled).close(CloseStatus.POLICY_VIOLATION.withReason("party-canceled"));
    }

    @Test
    @DisplayName("권한이 사라진 원격 수신자는 위치 전달 전에 닫는다")
    void closesUnauthorizedRemoteRecipientBeforeDelivery() throws Exception {
        FakeBus bus = new FakeBus();
        Node nodeA = node("node-a", bus);
        Node nodeB = node("node-b", bus);
        WebSocketSession recipient = openSession();
        nodeB.registry.register(20L, 3L, recipient);
        when(nodeB.access.canShare(20L, 2L)).thenReturn(true);
        when(nodeB.access.canShare(20L, 3L)).thenReturn(false);

        nodeA.service.publishLocation(20L, 2L, locationPayload());

        verify(recipient).close(CloseStatus.POLICY_VIOLATION);
        verify(recipient, never()).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("publish failure는 typed failure와 상태로 노출된다")
    void recordsPublishFailure() {
        RidePartyDistributedEventPublisher failing = event -> {
            throw new RidePartyDistributedPublishException(event.eventType().name(), new IllegalStateException("down"));
        };
        RidePartyDistributedStateService service = new RidePartyDistributedStateService(
                objectMapper, new RidePartySocketSessionRegistry(), mock(RidePartyLocationAccessService.class), failing, "node-a", clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.publishPartyCanceled(20L))
                .isInstanceOf(RidePartyDistributedPublishException.class);
        assertThat(service.status().publishHealthy()).isFalse();
    }

    @Test
    @DisplayName("publish 성공은 subscriber degraded 상태를 healthy로 바꾸지 않는다")
    void publishDoesNotRecoverSubscriberHealth() {
        Node node = node("node-a", new FakeBus());
        node.service.onBusFailure("listener");

        node.service.publishPartyCanceled(20L);

        assertThat(node.service.subscriberHealthy()).isFalse();
    }

    @Test
    @DisplayName("subscriber는 정상 callback 전까지 startup degraded다")
    void startsSubscriberDegraded() {
        Node node = node("node-a", new FakeBus());

        assertThat(node.service.subscriberHealthy()).isFalse();
    }

    @Test
    @DisplayName("zero recipient Redis publish는 typed failure다")
    void zeroRecipientPublishFailsClosed() {
        RidePartyRedisPubSubEventBus bus = new RidePartyRedisPubSubEventBus(mock(StringRedisTemplate.class),
                mock(RedisMessageListenerContainer.class), objectMapper, raw -> { });
        RidePartyDistributedEvent event = new RidePartyDistributedEvent(1, java.util.UUID.randomUUID().toString(), "node-a",
                RidePartyDistributedEvent.EventType.PARTY_CANCELED, 20L, null, com.fasterxml.jackson.databind.node.NullNode.instance,
                java.time.OffsetDateTime.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bus.publish(event))
                .isInstanceOf(RidePartyDistributedPublishException.class);
    }

    @Test
    @DisplayName("canonical UUID가 아닌 eventId는 거부한다")
    void rejectsNonCanonicalEventId() {
        Node node = node("node-b", new FakeBus());
        node.service.receive("{\"version\":1,\"eventId\":\"not-a-uuid\"}");

        assertThat(node.service.status().subscriberHealthy()).isFalse();
    }

    @Test
    @DisplayName("기록되지 않은 same-source event는 self echo로 허용하지 않고 drain한다")
    void rejectsUnknownSameSourceEvent() throws Exception {
        Node node = node("node-a", new FakeBus());
        WebSocketSession session = openSession();
        node.registry.register(20L, 2L, session);
        RidePartyDistributedEvent event = new RidePartyDistributedEvent(1, java.util.UUID.randomUUID().toString(), "node-a",
                RidePartyDistributedEvent.EventType.PARTY_CANCELED, 20L, null, com.fasterxml.jackson.databind.node.NullNode.instance,
                java.time.OffsetDateTime.now());

        node.service.receive(objectMapper.writeValueAsString(event));

        assertThat(node.service.subscriberState()).isEqualTo(RidePartySubscriberState.DEGRADED);
        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("distributed-bus-unavailable"));
    }

    @Test
    @DisplayName("production Redis listener failure는 active instance를 해제하고 다음 lifecycle retry에서 새 generation으로 복구한다")
    void listenerFailureDrainsAndLifecycleRetryReregisters() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        List<String> failures = new ArrayList<>();
        RidePartyRedisPubSubEventBus bus = new RidePartyRedisPubSubEventBus(redis, container, objectMapper,
                raw -> { throw new IllegalStateException("redis restart"); }, failures::add, () -> { });

        bus.start();
        org.mockito.ArgumentCaptor<org.springframework.data.redis.connection.MessageListener> listener = org.mockito.ArgumentCaptor.forClass(org.springframework.data.redis.connection.MessageListener.class);
        verify(container).addMessageListener(listener.capture(), org.mockito.ArgumentMatchers.any(Topic.class));
        listener.getValue().onMessage(mock(org.springframework.data.redis.connection.Message.class), null);
        bus.recoverIfNecessary();

        assertThat(failures).containsExactly("listener");
        assertThat(bus.isRunning()).isTrue();
        verify(container).removeMessageListener(org.mockito.ArgumentMatchers.same(listener.getValue()), org.mockito.ArgumentMatchers.any(Topic.class));
        verify(container, org.mockito.Mockito.times(2)).addMessageListener(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Topic.class));
    }

    @Test
    @DisplayName("publish failure는 등록된 local session을 fail-close한다")
    void publishFailureClosesLocalSessions() throws Exception {
        RidePartyDistributedEventPublisher failing = event -> { throw new RidePartyDistributedPublishException("LOCATION", new IllegalStateException()); };
        RidePartySocketSessionRegistry registry = new RidePartySocketSessionRegistry();
        WebSocketSession session = openSession();
        registry.register(20L, 2L, session);
        RidePartyDistributedStateService service = new RidePartyDistributedStateService(objectMapper, registry,
                mock(RidePartyLocationAccessService.class), failing, "node-a", clock);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.publishPartyCanceled(20L))
                .isInstanceOf(RidePartyDistributedPublishException.class);

        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("party-canceled"));
    }

    @Test
    @DisplayName("production Redis lifecycle는 readiness, disconnect drain, recovery와 terminal stop을 state graph에 반영한다")
    void productionLifecycleDrainsThenRecoversWithoutEscapingStopped() throws Exception {
        RidePartySocketSessionRegistry registry = new RidePartySocketSessionRegistry();
        RidePartyLocationAccessService access = mock(RidePartyLocationAccessService.class);
        RidePartyDistributedStateService state = new RidePartyDistributedStateService(
                objectMapper, registry, access, RidePartyDistributedEventPublisher.noOp(), "node-a", clock);
        when(access.canShare(20L, 2L)).thenReturn(true);
        WebSocketSession session = openSession();
        registry.register(20L, 2L, session);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.RedisCallback<String>>any())).thenReturn("PONG")
                .thenThrow(new IllegalStateException("redis disconnected"))
                .thenReturn("PONG");
        RidePartyRedisPubSubEventBus bus = new RidePartyRedisPubSubEventBus(
                redis, container, objectMapper, state::receive, state::onBusFailure,
                state::onSubscriptionStarting, state::onSubscriptionConfirmed, state::onBusStopped);

        bus.start();
        org.mockito.ArgumentCaptor<org.springframework.data.redis.connection.MessageListener> listener =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.redis.connection.MessageListener.class);
        verify(container).addMessageListener(listener.capture(), org.mockito.ArgumentMatchers.any(Topic.class));
        ((org.springframework.data.redis.connection.SubscriptionListener) listener.getValue())
                .onChannelSubscribed(RidePartyRedisPubSubEventBus.TOPIC.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1);
        assertThat(state.subscriberState()).isEqualTo(RidePartySubscriberState.HEALTHY);

        bus.confirmSubscriptionOrFail();
        bus.confirmSubscriptionOrFail();
        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("distributed-bus-unavailable"));
        assertThat(state.subscriberState()).isEqualTo(RidePartySubscriberState.DEGRADED);

        bus.recoverIfNecessary();
        org.mockito.ArgumentCaptor<org.springframework.data.redis.connection.MessageListener> recoveredListener =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.redis.connection.MessageListener.class);
        verify(container, org.mockito.Mockito.times(2)).addMessageListener(
                recoveredListener.capture(), org.mockito.ArgumentMatchers.any(Topic.class));
        ((org.springframework.data.redis.connection.SubscriptionListener) recoveredListener.getAllValues().get(1))
                .onChannelSubscribed(RidePartyRedisPubSubEventBus.TOPIC.getBytes(java.nio.charset.StandardCharsets.UTF_8), 1);
        assertThat(state.subscriberState()).isEqualTo(RidePartySubscriberState.HEALTHY);

        bus.stop();
        state.onSubscriptionConfirmed();
        bus.recoverIfNecessary();

        assertThat(state.subscriberState()).isEqualTo(RidePartySubscriberState.STOPPED);
        assertThat(bus.isRunning()).isFalse();
    }

    @Test
    @DisplayName("Redis listener는 reconnect drain 뒤 한 번만 다시 등록되고 stop에서 해제된다")
    void registersOncePerLifecycleAndUnregistersDuringDrain() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        RidePartyRedisPubSubEventBus bus = new RidePartyRedisPubSubEventBus(redis, container, objectMapper, raw -> { });

        bus.start();
        bus.start();
        bus.stop();
        bus.start();

        verify(container, org.mockito.Mockito.times(2)).addMessageListener(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.connection.MessageListener.class), org.mockito.ArgumentMatchers.any(Topic.class));
        verify(container).removeMessageListener(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.connection.MessageListener.class), org.mockito.ArgumentMatchers.any(Topic.class));
        assertThat(bus.isRunning()).isTrue();
    }

    @Test
    @DisplayName("권한이 유지된 재가입 session은 revoke event timestamp 때문에 닫히지 않는다")
    void preservesAuthorizedRejoinedSessionDuringRevoke() throws Exception {
        FakeBus bus = new FakeBus();
        Node nodeA = node("node-a", bus);
        Node nodeB = node("node-b", bus);
        WebSocketSession rejoined = openSession();
        nodeB.registry.register(20L, 2L, rejoined);
        when(nodeB.access.canShare(20L, 2L)).thenReturn(true);

        nodeA.service.publishMemberRevoked(20L, 2L);

        verify(rejoined, never()).close(org.mockito.ArgumentMatchers.any());
        verify(nodeB.access).canShare(20L, 2L);
    }

    @Test
    @DisplayName("remote eventId는 같은 local published ID여도 self echo로 무시하지 않는다")
    void appliesRemoteEventWhoseIdMatchesLocalPublishedEvent() throws Exception {
        RidePartyDistributedEvent[] published = new RidePartyDistributedEvent[1];
        RidePartySocketSessionRegistry registry = new RidePartySocketSessionRegistry();
        RidePartyLocationAccessService access = mock(RidePartyLocationAccessService.class);
        RidePartyDistributedStateService state = new RidePartyDistributedStateService(
                objectMapper, registry, access, event -> published[0] = event, "node-a", clock);
        WebSocketSession rejoined = openSession();
        registry.register(20L, 2L, rejoined);
        when(access.canShare(20L, 2L)).thenReturn(true);

        state.publishPartyCanceled(20L);
        RidePartyDistributedEvent remote = new RidePartyDistributedEvent(
                1, published[0].eventId(), "node-b", RidePartyDistributedEvent.EventType.PARTY_CANCELED,
                20L, null, com.fasterxml.jackson.databind.node.NullNode.instance, java.time.OffsetDateTime.now(clock));
        state.receive(objectMapper.writeValueAsString(remote));

        verify(access, org.mockito.Mockito.times(2)).canShare(20L, 2L);
        verify(rejoined, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("admission lock은 HEALTHY register와 drain을 같은 generation에서 직렬화한다")
    void atomicallyAdmitsOnlyHealthySessions() {
        RidePartySocketSessionRegistry registry = new RidePartySocketSessionRegistry();
        RidePartyDistributedStateService state = new RidePartyDistributedStateService(
                objectMapper, registry, mock(RidePartyLocationAccessService.class),
                RidePartyDistributedEventPublisher.noOp(), "node-a", clock);
        WebSocketSession admitted = openSession();
        WebSocketSession rejected = openSession();

        state.onSubscriptionStarting();
        state.onSubscriptionConfirmed();
        assertThat(state.registerIfSubscriberHealthy(20L, 2L, admitted)).isTrue();
        state.onBusFailure("ping");

        assertThat(registry.sessionsForParty(20L)).isEmpty();
        assertThat(state.registerIfSubscriberHealthy(20L, 3L, rejected)).isFalse();
    }
    private Node node(String nodeId, FakeBus bus) {
        RidePartySocketSessionRegistry registry = new RidePartySocketSessionRegistry();
        RidePartyLocationAccessService access = mock(RidePartyLocationAccessService.class);
        RidePartyDistributedStateService service = new RidePartyDistributedStateService(
                objectMapper, registry, access, bus, nodeId, clock);
        bus.subscribe(service::receive);
        return new Node(registry, access, service);
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private java.util.Map<String, Object> locationPayload() {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("partyId", 20L);
        payload.put("userId", 2L);
        payload.put("latitude", 37.5);
        payload.put("longitude", 127.0);
        payload.put("accuracyM", 8.0);
        payload.put("speedMps", null);
        payload.put("bearingDeg", null);
        payload.put("capturedAt", java.time.OffsetDateTime.now(clock).toString());
        return payload;
    }

    private record Node(RidePartySocketSessionRegistry registry, RidePartyLocationAccessService access,
                        RidePartyDistributedStateService service) { }

    private static class FakeBus implements RidePartyDistributedEventPublisher {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final List<Consumer<String>> subscribers = new ArrayList<>();
        private boolean duplicateNextDelivery;

        void subscribe(Consumer<String> subscriber) {
            subscribers.add(subscriber);
        }

        @Override
        public void publish(RidePartyDistributedEvent event) {
            try {
                String raw = mapper.writeValueAsString(event);
                for (Consumer<String> subscriber : subscribers) {
                    subscriber.accept(raw);
                    if (duplicateNextDelivery) {
                        subscriber.accept(raw);
                    }
                }
                duplicateNextDelivery = false;
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
