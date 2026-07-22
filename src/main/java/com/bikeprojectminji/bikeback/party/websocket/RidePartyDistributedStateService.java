package com.bikeprojectminji.bikeback.party.websocket;

import com.bikeprojectminji.bikeback.party.service.RidePartyLocationAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

@Component
public class RidePartyDistributedStateService {
    private static final Logger log = LoggerFactory.getLogger(RidePartyDistributedStateService.class);
    private static final int MAX_RAW_BYTES = 8_192;
    private static final int MAX_EVENT_ID_BYTES = 64;
    private static final int MAX_NODE_ID_BYTES = 160;
    private static final int MAX_SEEN_EVENTS = 10_000;
    private static final Duration SEEN_EVENT_TTL = Duration.ofMinutes(10);
    private static final Duration MAX_EVENT_AGE = Duration.ofMinutes(5);
    private static final String NODE_ID = System.getenv("HOSTNAME") == null || System.getenv("HOSTNAME").isBlank()
            ? "party-" + UUID.randomUUID() : System.getenv("HOSTNAME") + "-" + UUID.randomUUID();

    private final ObjectMapper objectMapper;
    private final RidePartySocketSessionRegistry sessionRegistry;
    private final RidePartyLocationAccessService locationAccessService;
    private final RidePartyDistributedEventPublisher publisher;
    private final String nodeId;
    private final Clock clock;
    private final Map<String, OffsetDateTime> seen = new ConcurrentHashMap<>();
    private final AtomicReference<RuntimeState> runtimeState =
            new AtomicReference<>(new RuntimeState(RidePartySubscriberState.STARTING, true, null));

    public RidePartyDistributedStateService(ObjectMapper mapper, RidePartySocketSessionRegistry registry,
            RidePartyLocationAccessService access, RidePartyRedisPubSubEventBus publisher,
            @Value("${HOSTNAME:}") String ignoredHostName) {
        this(mapper, registry, access, publisher, NODE_ID, Clock.systemUTC());
    }

    RidePartyDistributedStateService(ObjectMapper mapper, RidePartySocketSessionRegistry registry,
            RidePartyLocationAccessService access, RidePartyDistributedEventPublisher publisher, String nodeId, Clock clock) {
        if (!nodeId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,159}")) throw new IllegalArgumentException("Unsafe node id.");
        this.objectMapper = mapper; this.sessionRegistry = registry; this.locationAccessService = access;
        this.publisher = publisher; this.nodeId = nodeId; this.clock = clock;
    }

    public void publishLocation(Long partyId, Long userId, Object payload) { publish(RidePartyDistributedEvent.EventType.LOCATION, partyId, userId, objectMapper.valueToTree(payload)); }
    public void publishMemberRevoked(Long partyId, Long userId) {
        sessionRegistry.closeMemberBefore(partyId, userId, OffsetDateTime.now(clock));
        publish(RidePartyDistributedEvent.EventType.MEMBER_REVOKED, partyId, userId, NullNode.instance);
    }
    public void publishPartyCanceled(Long partyId) {
        sessionRegistry.closePartyBefore(partyId, OffsetDateTime.now(clock));
        publish(RidePartyDistributedEvent.EventType.PARTY_CANCELED, partyId, null, NullNode.instance);
    }

    public void receive(String raw) {
        try {
            if (raw == null || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RAW_BYTES) throw new IllegalArgumentException("Oversized envelope.");
            JsonNode root = objectMapper.readTree(raw);
            requireFields(root, Set.of("version", "eventId", "sourceNodeId", "eventType", "partyId", "userId", "payload", "emittedAt"));
            validateEnvelopeShape(root);
            RidePartyDistributedEvent event = objectMapper.treeToValue(root, RidePartyDistributedEvent.class);
            validate(event);
            if (nodeId.equals(event.sourceNodeId())) {
                if (duplicate(event.eventId())) return;
                throw new IllegalArgumentException("Unknown same-source event.");
            }
            if (duplicate(event.eventId())) return;
            apply(event);
        } catch (Exception exception) {
            degradeAndDrain("receive");
            log.warn("party websocket redis event rejected failureType={}", exception.getClass().getSimpleName());
        }
    }

    public synchronized void onBusFailure(String operation) { degradeAndDrain(operation); }
    public synchronized void onSubscriptionStarting() {
        transition(current -> current.withSubscriber(RidePartySubscriberState.RECOVERING, null));
    }
    public synchronized void onSubscriptionConfirmed() {
        if (revalidateAllSessions()) {
            transition(current -> current.subscriberState() == RidePartySubscriberState.STOPPED
                    ? current : current.withSubscriber(RidePartySubscriberState.HEALTHY, null));
        }
    }
    public synchronized void onBusStopped() {
        transition(current -> current.withSubscriber(RidePartySubscriberState.STOPPED, "stopped"));
        sessionRegistry.closeAll();
    }
    public boolean subscriberHealthy() { return runtimeState.get().subscriberState() == RidePartySubscriberState.HEALTHY; }
    public RidePartySubscriberState subscriberState() { return runtimeState.get().subscriberState(); }
    public DistributedBusStatus status() {
        RuntimeState current = runtimeState.get();
        return new DistributedBusStatus(current.publishHealthy(), subscriberHealthy(), current.lastFailureOperation());
    }

    private void publish(RidePartyDistributedEvent.EventType type, Long partyId, Long userId, JsonNode payload) {
        RidePartyDistributedEvent event = new RidePartyDistributedEvent(1, UUID.randomUUID().toString(), nodeId, type, partyId, userId, payload, OffsetDateTime.now(clock));
        try {
            rememberLocal(event.eventId());
            publisher.publish(event);
            transition(current -> current.withPublish(true, subscriberHealthy() ? null : "subscriber"));
        } catch (RidePartyDistributedPublishException exception) {
            transition(current -> current.withPublish(false, "publish"));
            sessionRegistry.closeAll();
            throw exception;
        }
    }

    private void apply(RidePartyDistributedEvent event) throws Exception {
        switch (event.eventType()) {
            case LOCATION -> broadcast(event.partyId(), event.userId(), event.payload());
            case MEMBER_REVOKED -> sessionRegistry.closeMemberBefore(event.partyId(), event.userId(), event.emittedAt());
            case PARTY_CANCELED -> sessionRegistry.closePartyBefore(event.partyId(), event.emittedAt());
        }
    }
    void revalidateMember(Long partyId, Long userId) {
        try {
            for (RidePartySocketSessionRegistry.PartySocketSession entry : sessionRegistry.sessionsForParty(partyId))
                if (entry.userId().equals(userId) && !locationAccessService.canShare(partyId, userId)) sessionRegistry.close(entry.session(), CloseStatus.POLICY_VIOLATION.withReason("member-left"));
        } catch (RuntimeException exception) { degradeAndDrain("revalidation"); throw exception; }
    }
    void revalidateParty(Long partyId) {
        try {
            for (RidePartySocketSessionRegistry.PartySocketSession entry : sessionRegistry.sessionsForParty(partyId))
                if (!locationAccessService.canShare(partyId, entry.userId())) sessionRegistry.close(entry.session(), CloseStatus.POLICY_VIOLATION.withReason("party-canceled"));
        } catch (RuntimeException exception) { degradeAndDrain("revalidation"); throw exception; }
    }
    private void broadcast(Long partyId, Long senderUserId, JsonNode payload) throws Exception {
        if (!locationAccessService.canShare(partyId, senderUserId)) return;
        TextMessage message = new TextMessage(objectMapper.writeValueAsString(Map.of("type", "location", "data", payload)));
        for (RidePartySocketSessionRegistry.PartySocketSession entry : sessionRegistry.sessionsForParty(partyId)) {
            if (!locationAccessService.canShare(entry.partyId(), entry.userId())) sessionRegistry.close(entry.session(), CloseStatus.POLICY_VIOLATION);
            else sessionRegistry.send(entry, message);
        }
    }
    public synchronized void revalidateAllSessionsOrDrain() {
        revalidateAllSessions();
    }
    private boolean revalidateAllSessions() {
        try {
        for (RidePartySocketSessionRegistry.PartySocketSession entry : sessionRegistry.sessionsForParty(null)) {
            if (!locationAccessService.canShare(entry.partyId(), entry.userId())) sessionRegistry.close(entry.session(), CloseStatus.POLICY_VIOLATION);
        }
        return true;
        } catch (RuntimeException exception) { onBusFailure("revalidation"); }
        return false;
    }
    private synchronized void degradeAndDrain(String operation) {
        transition(current -> current.subscriberState() == RidePartySubscriberState.STOPPED
                ? current : current.withSubscriber(RidePartySubscriberState.DEGRADED, operation));
        sessionRegistry.closeAll();
    }
    private boolean duplicate(String id) {
        OffsetDateTime now = OffsetDateTime.now(clock); seen.entrySet().removeIf(e -> e.getValue().plus(SEEN_EVENT_TTL).isBefore(now));
        if (seen.putIfAbsent(id, now) != null) return true;
        if (seen.size() > MAX_SEEN_EVENTS) seen.keySet().stream().limit(seen.size() - MAX_SEEN_EVENTS).forEach(seen::remove);
        return false;
    }
    private void rememberLocal(String id) { duplicate(id); }
    private void validate(RidePartyDistributedEvent e) throws Exception {
        if (e.eventType()==null || e.version()!=1 || e.eventId()==null || e.eventId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_EVENT_ID_BYTES || !isUuid(e.eventId())
                || e.sourceNodeId()==null || e.sourceNodeId().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_NODE_ID_BYTES || !e.sourceNodeId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{2,159}")
                || e.partyId()==null || e.partyId()<=0 || e.payload()==null || e.emittedAt()==null || e.emittedAt().isBefore(OffsetDateTime.now(clock).minus(MAX_EVENT_AGE)) || e.emittedAt().isAfter(OffsetDateTime.now(clock).plusMinutes(1))) throw new IllegalArgumentException("Invalid envelope.");
        if ((e.eventType()==RidePartyDistributedEvent.EventType.LOCATION || e.eventType()==RidePartyDistributedEvent.EventType.MEMBER_REVOKED) && (e.userId()==null || e.userId()<=0)) throw new IllegalArgumentException("Missing user.");
        if (e.eventType()==RidePartyDistributedEvent.EventType.PARTY_CANCELED && e.userId()!=null) throw new IllegalArgumentException("Unexpected user.");
        if (e.eventType()!=RidePartyDistributedEvent.EventType.LOCATION && !e.payload().isNull()) throw new IllegalArgumentException("Unexpected payload.");
        if (e.eventType()==RidePartyDistributedEvent.EventType.LOCATION) {
            requireFields(e.payload(), Set.of("partyId", "userId", "latitude", "longitude", "accuracyM", "speedMps", "bearingDeg", "capturedAt"));
            if (!e.payload().path("partyId").isIntegralNumber() || !e.payload().path("userId").isIntegralNumber() || e.payload().path("partyId").asLong()!=e.partyId() || e.payload().path("userId").asLong()!=e.userId()) throw new IllegalArgumentException("Forged location identity.");
            requireNumber(e.payload(), "latitude");
            requireNumber(e.payload(), "longitude");
            requireNullableNumber(e.payload(), "accuracyM");
            requireNullableNumber(e.payload(), "speedMps");
            requireNullableNumber(e.payload(), "bearingDeg");
            if (!e.payload().path("capturedAt").isTextual()) throw new IllegalArgumentException("Invalid capturedAt.");
            com.fasterxml.jackson.databind.node.ObjectNode telemetry = ((com.fasterxml.jackson.databind.node.ObjectNode) e.payload()).deepCopy();
            telemetry.remove("partyId");
            telemetry.remove("userId");
            RidePartyLocationMessage location = objectMapper.treeToValue(telemetry, RidePartyLocationMessage.class);
            location.validate();
            location.validateCapturedAt(OffsetDateTime.now(clock));
        }
    }
    private void validateEnvelopeShape(JsonNode root) {
        if (!root.path("version").isIntegralNumber() || !root.path("eventId").isTextual()
                || !root.path("sourceNodeId").isTextual() || !root.path("eventType").isTextual()
                || !root.path("partyId").isIntegralNumber()
                || !(root.path("userId").isNull() || root.path("userId").isIntegralNumber())
                || !(root.path("emittedAt").isTextual() || root.path("emittedAt").isNumber())) throw new IllegalArgumentException("Invalid envelope shape.");
    }
    private void requireNumber(JsonNode payload, String field) {
        if (!payload.path(field).isNumber()) throw new IllegalArgumentException("Invalid " + field + ".");
    }
    private void requireNullableNumber(JsonNode payload, String field) {
        if (!(payload.path(field).isNull() || payload.path(field).isNumber())) throw new IllegalArgumentException("Invalid " + field + ".");
    }
    private void requireFields(JsonNode node, Set<String> fields) { if (!node.isObject() || node.size()!=fields.size()) throw new IllegalArgumentException("Malformed fields."); for (String f: fields) if (!node.has(f)) throw new IllegalArgumentException("Missing field."); node.fieldNames().forEachRemaining(f -> { if(!fields.contains(f)) throw new IllegalArgumentException("Unknown field."); }); }
    private boolean isUuid(String value) { try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException ex) { return false; } }
    public record DistributedBusStatus(boolean publishHealthy, boolean subscriberHealthy, String lastFailureOperation) {
        boolean healthy(){ return publishHealthy && subscriberHealthy; }
        static DistributedBusStatus available(){return new DistributedBusStatus(true,true,null);}
        static DistributedBusStatus failure(String op){return new DistributedBusStatus(false,false,op);}
    }
    private void transition(UnaryOperator<RuntimeState> transition) {
        runtimeState.updateAndGet(transition);
    }

    private record RuntimeState(RidePartySubscriberState subscriberState, boolean publishHealthy, String lastFailureOperation) {
        RuntimeState withSubscriber(RidePartySubscriberState next, String failure) { return new RuntimeState(next, publishHealthy, failure); }
        RuntimeState withPublish(boolean next, String failure) { return new RuntimeState(subscriberState, next, failure); }
    }
}
