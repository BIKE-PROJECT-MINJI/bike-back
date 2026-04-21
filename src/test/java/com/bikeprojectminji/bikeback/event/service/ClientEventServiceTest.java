package com.bikeprojectminji.bikeback.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.event.dto.ClientEventBatchResponse;
import com.bikeprojectminji.bikeback.event.dto.ClientEventResponse;
import com.bikeprojectminji.bikeback.event.dto.CreateClientEventBatchRequest;
import com.bikeprojectminji.bikeback.event.dto.CreateClientEventRequest;
import com.bikeprojectminji.bikeback.event.entity.ClientEventEntity;
import com.bikeprojectminji.bikeback.event.repository.ClientEventRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ClientEventServiceTest {

    @Mock
    private ClientEventRepository clientEventRepository;

    @Mock
    private AuthService authService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ClientEventService clientEventService;

    @BeforeEach
    void setUp() {
        clientEventService = new ClientEventService(clientEventRepository, authService, objectMapper);
    }

    @Test
    @DisplayName("정상 이벤트 저장은 인증 사용자 기준으로 eventId와 receivedAt을 반환한다")
    void saveEventReturnsSavedResponse() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(clientEventRepository.save(any(ClientEventEntity.class))).willAnswer(invocation -> {
            ClientEventEntity entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, "id", 1001L);
            return entity;
        });

        ClientEventResponse response = clientEventService.saveEvent("1", new CreateClientEventRequest(
                "ride_start_clicked",
                1,
                "sess_001",
                OffsetDateTime.parse("2026-04-21T13:20:00+09:00"),
                "course_detail",
                12L,
                null,
                "1.0.0",
                "android",
                "mobile",
                "granted",
                objectMapper.createObjectNode().put("source", "course_detail_button")
        ));

        assertThat(response.eventId()).isEqualTo(1001L);
        assertThat(response.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("민감 키가 포함된 properties는 제거하고 저장한다")
    void saveEventRemovesSensitiveKeys() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(clientEventRepository.save(any(ClientEventEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

        var properties = objectMapper.createObjectNode();
        properties.put("token", "secret-token");
        properties.put("reason", "LOCATION_PERMISSION_DENIED");

        clientEventService.saveEvent("1", new CreateClientEventRequest(
                "ride_start_blocked", 1, "sess_001", null, "course_detail", 12L, null,
                "1.0.0", "android", "mobile", "denied", properties
        ));

        ArgumentCaptor<ClientEventEntity> captor = ArgumentCaptor.forClass(ClientEventEntity.class);
        org.mockito.Mockito.verify(clientEventRepository).save(captor.capture());
        assertThat(captor.getValue().getPropertiesJson().has("token")).isFalse();
    }

    @Test
    @DisplayName("batch size가 50개를 넘으면 저장을 거부한다")
    void saveEventsRejectsOversizedBatch() {
        List<CreateClientEventRequest> events = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> new CreateClientEventRequest(
                        "course_impression", 1, "sess_001", null, "course_list", 1L, null,
                        "1.0.0", "android", "mobile", "granted", objectMapper.createObjectNode()
                ))
                .toList();

        assertThatThrownBy(() -> clientEventService.saveEvents("1", new CreateClientEventBatchRequest(events)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("events는 최대 50개까지 저장할 수 있습니다.");
    }

    @Test
    @DisplayName("정상 batch 저장은 저장 개수를 반환한다")
    void saveEventsReturnsSavedCount() {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(clientEventRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

        ClientEventBatchResponse response = clientEventService.saveEvents("1", new CreateClientEventBatchRequest(List.of(
                new CreateClientEventRequest("course_list_viewed", 1, "sess_001", null, "course_list", null, null, "1.0.0", "android", "mobile", "granted", objectMapper.createObjectNode()),
                new CreateClientEventRequest("course_selected", 1, "sess_001", null, "course_list", 12L, null, "1.0.0", "android", "mobile", "granted", objectMapper.createObjectNode())
        )));

        assertThat(response.savedCount()).isEqualTo(2);
    }
}
