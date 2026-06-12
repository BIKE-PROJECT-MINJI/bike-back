package com.bikeprojectminji.bikeback.airoute.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionCreateRequest;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.PromoteAiRouteCandidateRequest;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "ai-route.generation.quota.per-minute=100",
        "ai-route.generation.quota.per-day=100"
})
@ActiveProfiles("test")
class AiRouteGenerationSessionServiceIntegrationTest {

    @Autowired
    private AiRouteGenerationSessionService sessionService;

    @Autowired
    private AiRouteGenerationSessionRepository sessionRepository;

    @Autowired
    private AiRouteCandidateRepository candidateRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Autowired
    private CourseRoutePointRepository courseRoutePointRepository;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private AiRoutePlannerService aiRoutePlannerService;

    @BeforeEach
    void setUp() {
        courseRoutePointRepository.deleteAll();
        courseRepository.deleteAll();
        candidateRepository.deleteAll();
        sessionRepository.deleteAll();

        UserEntity user = new UserEntity("external-1", "owner@example.test", "hash", "소유자", null);
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        given(authService.findUserBySubject("1")).willReturn(user);

        UserEntity other = new UserEntity("external-2", "other@example.test", "hash", "타인", null);
        org.springframework.test.util.ReflectionTestUtils.setField(other, "id", 2L);
        given(authService.findUserBySubject("2")).willReturn(other);
    }

    @Test
    @DisplayName("AI worker가 실패해도 backend fallback 후보를 세션에 저장한다")
    void createSessionStoresFallbackCandidateWhenAiWorkerFailed() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(false));

        AiRouteGenerationSessionResponse response = sessionService.createSession("1", createRequest());

        assertThat(response.status()).isEqualTo("FALLBACK_READY");
        assertThat(response.fallbackUsed()).isTrue();
        assertThat(response.provider()).isEqualTo("backend-fallback");
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().get(0).routePointCount()).isEqualTo(2);
        assertThat(sessionRepository.findAll()).hasSize(1);
        assertThat(candidateRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("AI 코스 후보를 승격하면 실제 Course와 route point 복사본을 만든다")
    void promoteCandidateCreatesCourseAndRoutePoints() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true));
        AiRouteGenerationSessionResponse session = sessionService.createSession("1", createRequest());
        Long candidateId = session.candidates().get(0).candidateId();

        AiRoutePromotedCourseResponse promoted = sessionService.promoteCandidate(
                "1",
                session.sessionId(),
                candidateId,
                new PromoteAiRouteCandidateRequest("남산 업힐 후보", "검수 후 저장", "PRIVATE")
        );

        CourseEntity course = courseRepository.findById(promoted.courseId()).orElseThrow();
        assertThat(promoted.routePointCount()).isEqualTo(2);
        assertThat(course.getTitle()).isEqualTo("남산 업힐 후보");
        assertThat(course.getSourceAiRouteSessionId()).isEqualTo(session.sessionId());
        assertThat(course.getSourceAiRouteCandidateId()).isEqualTo(candidateId);
        assertThat(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(course.getId())).hasSize(2);
    }

    @Test
    @DisplayName("다른 사용자의 AI 코스 생성 세션은 조회할 수 없다")
    void getSessionRejectsOtherOwner() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true));
        AiRouteGenerationSessionResponse session = sessionService.createSession("1", createRequest());

        assertThatThrownBy(() -> sessionService.getSession("2", session.sessionId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("AI 코스 생성 세션을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("이미 승격된 AI 코스 후보는 다시 승격할 수 없다")
    void promoteCandidateRejectsAlreadyPromotedCandidate() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true));
        AiRouteGenerationSessionResponse session = sessionService.createSession("1", createRequest());
        Long candidateId = session.candidates().get(0).candidateId();
        PromoteAiRouteCandidateRequest request = new PromoteAiRouteCandidateRequest("남산 업힐 후보", "검수 후 저장", "PRIVATE");
        sessionService.promoteCandidate("1", session.sessionId(), candidateId, request);

        assertThatThrownBy(() -> sessionService.promoteCandidate("1", session.sessionId(), candidateId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 Course로 저장된 AI 코스 후보입니다.");
    }

    private AiRouteGenerationSessionCreateRequest createRequest() {
        return new AiRouteGenerationSessionCreateRequest(
                BigDecimal.valueOf(37.4812),
                BigDecimal.valueOf(126.9527),
                BigDecimal.valueOf(37.5512),
                BigDecimal.valueOf(126.9882),
                "남산",
                "SCENERY_FIRST",
                "CLIMB_FIRST",
                "오르막 많은 코스 추천"
        );
    }

    private AiRoutePlanResponse plan(boolean aiGenerated) {
        return new AiRoutePlanResponse(
                "plan-1",
                "READY",
                "남산 업힐 후보",
                "HIGH",
                null,
                null,
                List.of(
                        new AiRoutePointResponse(BigDecimal.valueOf(37.4812), BigDecimal.valueOf(126.9527), "출발", BigDecimal.valueOf(42)),
                        new AiRoutePointResponse(BigDecimal.valueOf(37.5512), BigDecimal.valueOf(126.9882), "도착", BigDecimal.valueOf(243))
                ),
                List.of(),
                List.of(),
                84,
                null,
                null,
                List.of(),
                aiGenerated
        );
    }
}
