package com.bikeprojectminji.bikeback.event.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.event.dto.ClientEventBatchResponse;
import com.bikeprojectminji.bikeback.event.dto.ClientEventResponse;
import com.bikeprojectminji.bikeback.event.dto.CreateClientEventBatchRequest;
import com.bikeprojectminji.bikeback.event.dto.CreateClientEventRequest;
import com.bikeprojectminji.bikeback.event.entity.ClientEventEntity;
import com.bikeprojectminji.bikeback.event.repository.ClientEventRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientEventService {

    private static final int MAX_BATCH_SIZE = 50;
    private static final int MAX_PROPERTIES_JSON_LENGTH = 4000;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "accesstoken", "refreshtoken", "authorization", "jwt", "secret"
    );

    private final ClientEventRepository clientEventRepository;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public ClientEventService(ClientEventRepository clientEventRepository, AuthService authService, ObjectMapper objectMapper) {
        this.clientEventRepository = clientEventRepository;
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ClientEventResponse saveEvent(String subject, CreateClientEventRequest request) {
        UserEntity user = authService.findUserBySubject(subject);
        ClientEventEntity saved = clientEventRepository.save(toEntity(user.getId(), validateAndNormalize(request)));
        return new ClientEventResponse(saved.getId(), saved.getReceivedAtServer());
    }

    @Transactional
    public ClientEventBatchResponse saveEvents(String subject, CreateClientEventBatchRequest request) {
        if (request == null || request.events() == null || request.events().isEmpty()) {
            throw new BadRequestException("events는 비어 있을 수 없습니다.");
        }
        if (request.events().size() > MAX_BATCH_SIZE) {
            throw new BadRequestException("events는 최대 50개까지 저장할 수 있습니다.");
        }
        UserEntity user = authService.findUserBySubject(subject);
        List<ClientEventEntity> entities = request.events().stream()
                .map(this::validateAndNormalize)
                .map(event -> toEntity(user.getId(), event))
                .toList();
        clientEventRepository.saveAll(entities);
        return new ClientEventBatchResponse(entities.size());
    }

    private CreateClientEventRequest validateAndNormalize(CreateClientEventRequest request) {
        if (request == null) {
            throw new BadRequestException("이벤트 요청 본문이 필요합니다.");
        }
        if (request.eventName() == null || request.eventName().isBlank()) {
            throw new BadRequestException("eventName은 비어 있을 수 없습니다.");
        }
        if (request.eventName().length() > 100) {
            throw new BadRequestException("eventName은 최대 100자까지 허용합니다.");
        }
        if (!request.eventName().matches("^[a-z0-9_]+$")) {
            throw new BadRequestException("eventName은 소문자, 숫자, 언더스코어만 허용합니다.");
        }
        if (request.sessionId() != null && request.sessionId().length() > 100) {
            throw new BadRequestException("sessionId는 최대 100자까지 허용합니다.");
        }
        if (request.screenName() != null && request.screenName().length() > 100) {
            throw new BadRequestException("screenName은 최대 100자까지 허용합니다.");
        }

        JsonNode sanitizedProperties = sanitizeProperties(request.properties());
        try {
            if (objectMapper.writeValueAsString(sanitizedProperties).length() > MAX_PROPERTIES_JSON_LENGTH) {
                throw new BadRequestException("properties는 최대 4000자까지 허용합니다.");
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("properties를 직렬화할 수 없습니다.");
        }

        return new CreateClientEventRequest(
                request.eventName(),
                request.eventVersion() == null ? 1 : request.eventVersion(),
                request.sessionId(),
                request.occurredAtClient(),
                request.screenName(),
                request.courseId(),
                request.rideRecordId(),
                request.appVersion(),
                request.osName(),
                request.deviceType(),
                request.locationPermissionState(),
                sanitizedProperties
        );
    }

    private JsonNode sanitizeProperties(JsonNode properties) {
        if (properties == null || properties.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        JsonNode copy = properties.deepCopy();
        sanitizeNode(copy);
        return copy;
    }

    private void sanitizeNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            List<String> keysToRemove = new ArrayList<>();
            var fieldNames = objectNode.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (SENSITIVE_KEYS.contains(fieldName.toLowerCase(Locale.ROOT))) {
                    keysToRemove.add(fieldName);
                    continue;
                }
                sanitizeNode(objectNode.get(fieldName));
            }
            keysToRemove.forEach(objectNode::remove);
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                sanitizeNode(child);
            }
        }
    }

    private ClientEventEntity toEntity(Long userId, CreateClientEventRequest request) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ClientEventEntity(
                request.eventName(),
                request.eventVersion(),
                userId,
                request.sessionId(),
                request.occurredAtClient(),
                now,
                request.screenName(),
                request.courseId(),
                request.rideRecordId(),
                request.appVersion(),
                request.osName(),
                request.deviceType(),
                request.locationPermissionState(),
                request.properties(),
                now
        );
    }
}
