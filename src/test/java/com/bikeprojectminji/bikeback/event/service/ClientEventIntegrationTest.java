package com.bikeprojectminji.bikeback.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.event.repository.ClientEventRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientEventIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClientEventRepository clientEventRepository;

    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void setUp() {
        UserEntity user = new UserEntity("external-1", "bikeoasis@example.com", null, "bikeoasis", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        org.mockito.BDDMockito.given(authService.findUserBySubject("1")).willReturn(user);
    }

    @Test
    @DisplayName("이벤트 저장 API는 DB에 이벤트를 저장하고 민감 키를 제거한다")
    void saveEventStoresSanitizedEvent() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventName": "ride_start_blocked",
                                  "eventVersion": 1,
                                  "sessionId": "sess_test_001",
                                  "screenName": "course_detail",
                                  "courseId": 12,
                                  "properties": {
                                    "reason": "LOCATION_PERMISSION_DENIED",
                                    "token": "should_be_removed"
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        var saved = clientEventRepository.findAll();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getPropertiesJson().has("token")).isFalse();
    }

    @Test
    @DisplayName("이벤트 batch API는 여러 건을 DB에 저장한다")
    void saveEventsBatchStoresMultipleRows() throws Exception {
        long beforeCount = clientEventRepository.count();

        mockMvc.perform(post("/api/v1/events/batch")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "events": [
                                    {"eventName": "course_list_viewed", "eventVersion": 1, "screenName": "course_list", "properties": {}},
                                    {"eventName": "course_selected", "eventVersion": 1, "screenName": "course_list", "courseId": 12, "properties": {"source": "recommended"}}
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(clientEventRepository.count()).isEqualTo(beforeCount + 2);
    }
}
