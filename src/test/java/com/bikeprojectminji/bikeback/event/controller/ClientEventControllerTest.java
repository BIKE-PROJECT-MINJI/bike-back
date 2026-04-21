package com.bikeprojectminji.bikeback.event.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.event.dto.ClientEventBatchResponse;
import com.bikeprojectminji.bikeback.event.dto.ClientEventResponse;
import com.bikeprojectminji.bikeback.event.service.ClientEventService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ClientEventController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class ClientEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClientEventService clientEventService;

    @Test
    @DisplayName("이벤트 저장 API는 인증된 사용자의 저장 결과를 응답한다")
    void saveEventReturnsWrappedResponse() throws Exception {
        given(clientEventService.saveEvent(org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.any()))
                .willReturn(new ClientEventResponse(1001L, java.time.OffsetDateTime.parse("2026-04-21T13:20:03+09:00")));

        mockMvc.perform(post("/api/v1/events")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventName": "ride_start_clicked",
                                  "eventVersion": 1,
                                  "sessionId": "sess_test_001",
                                  "occurredAtClient": "2026-04-21T13:20:00+09:00",
                                  "screenName": "course_detail",
                                  "courseId": 1,
                                  "appVersion": "1.0.0",
                                  "osName": "android",
                                  "deviceType": "mobile",
                                  "locationPermissionState": "granted",
                                  "properties": {
                                    "source": "course_detail_button"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(1001));
    }

    @Test
    @DisplayName("이벤트 저장 API는 비로그인 요청이면 401을 반환한다")
    void saveEventReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("이벤트 batch API는 인증된 사용자의 저장 개수를 응답한다")
    void saveEventsBatchReturnsWrappedResponse() throws Exception {
        given(clientEventService.saveEvents(org.mockito.ArgumentMatchers.eq("1"), org.mockito.ArgumentMatchers.any()))
                .willReturn(new ClientEventBatchResponse(2));

        mockMvc.perform(post("/api/v1/events/batch")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "events": [
                                    {
                                      "eventName": "course_list_viewed",
                                      "eventVersion": 1,
                                      "sessionId": "sess_test_001",
                                      "screenName": "course_list",
                                      "properties": {}
                                    },
                                    {
                                      "eventName": "course_selected",
                                      "eventVersion": 1,
                                      "sessionId": "sess_test_001",
                                      "screenName": "course_list",
                                      "courseId": 12,
                                      "properties": { "source": "recommended" }
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.savedCount").value(2));
    }
}
