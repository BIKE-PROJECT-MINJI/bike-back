package com.bikeprojectminji.bikeback.beta.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.beta.dto.BetaInvitationVerifyResponse;
import com.bikeprojectminji.bikeback.beta.service.BetaInvitationService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BetaInvitationController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=900"
})
class BetaInvitationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BetaInvitationService betaInvitationService;

    @Test
    @DisplayName("초대 코드 검증 API는 유효한 코드의 만료 시각을 반환한다")
    void verifyInvitationCodeReturnsExpiry() throws Exception {
        given(betaInvitationService.verify("BIKE-2026"))
                .willReturn(new BetaInvitationVerifyResponse(true, Instant.parse("2026-06-22T00:00:00Z")));

        mockMvc.perform(post("/api/v1/beta-invitations/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "BIKE-2026"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-06-22T00:00:00Z"));
    }

    @Test
    @DisplayName("초대 코드 검증 API는 코드가 비어 있으면 400을 반환한다")
    void verifyInvitationCodeReturnsBadRequestWhenCodeIsBlank() throws Exception {
        mockMvc.perform(post("/api/v1/beta-invitations/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": " "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("초대 코드는 비어 있을 수 없습니다."));
    }
}
