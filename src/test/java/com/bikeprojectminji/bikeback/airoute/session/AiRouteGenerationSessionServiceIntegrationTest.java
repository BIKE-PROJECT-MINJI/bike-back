package com.bikeprojectminji.bikeback.airoute.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
                .willReturn(plan(false, 0), plan(false, 1), plan(false, 2));

        AiRouteGenerationSessionResponse response = sessionService.createSession("1", createRequest());

        assertThat(response.status()).isEqualTo("FALLBACK_READY");
        assertThat(response.fallbackUsed()).isTrue();
        assertThat(response.provider()).isEqualTo("backend-fallback");
        assertThat(response.candidates()).hasSize(3);
        assertThat(response.candidates().get(0).routePointCount()).isEqualTo(2);
        assertThat(sessionRepository.findAll()).hasSize(1);
        assertThat(candidateRepository.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("정상 세션은 서로 다른 후보 세 개와 고유 candidate id를 저장한다")
    void createSessionStoresThreeDistinctCandidates() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true, 0), plan(true, 1), plan(true, 2));

        AiRouteGenerationSessionResponse response = sessionService.createSession("1", createRequest());

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.candidates()).hasSize(3);
        assertThat(response.candidates()).extracting(candidate -> candidate.candidateId()).doesNotHaveDuplicates();
        assertThat(response.candidates()).extracting(candidate -> candidate.routePoints().get(1).lat())
                .doesNotHaveDuplicates();
        verify(aiRoutePlannerService, times(3)).plan(eq("1"), any(AiRoutePlanRequest.class));
    }

    @Test
    @DisplayName("후보 두 개 뒤 경로 없음은 저장된 두 후보와 PARTIAL metadata로 응답한다")
    void createSessionStoresPartialCandidatesWhenOneAttemptHasNoRoute() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true, 0), plan(true, 1))
                .willThrow(new RouteNotFoundException("no route"));

        AiRouteGenerationSessionResponse response = sessionService.createSession("1", createRequest());

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.fallbackUsed()).isTrue();
        assertThat(response.fallbackReason()).contains("NO_ROUTE=1");
        assertThat(response.candidates()).hasSize(2);
        assertThat(candidateRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("세 시도 모두 경로 없음이면 session을 남기지 않고 422 원인 예외를 던진다")
    void createSessionThrowsRouteNotFoundWithoutPartialRows() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willThrow(new RouteNotFoundException("no route"));

        assertThatThrownBy(() -> sessionService.createSession("1", createRequest()))
                .isInstanceOf(RouteNotFoundException.class);
        assertThat(sessionRepository.findAll()).isEmpty();
        assertThat(candidateRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("세 시도 모두 provider 장애면 session을 남기지 않고 retry 가능한 503 원인을 던진다")
    void createSessionThrowsProviderUnavailableWithoutPartialRows() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willThrow(new RoutingProviderUnavailableException("down", 9));

        assertThatThrownBy(() -> sessionService.createSession("1", createRequest()))
                .isInstanceOfSatisfying(RoutingProviderUnavailableException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isEqualTo(9));
        assertThat(sessionRepository.findAll()).isEmpty();
        assertThat(candidateRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("AI provider 호출은 DB 트랜잭션 밖에서 수행해 connection 점유 시간을 늘리지 않는다")
    void createSessionCallsPlannerOutsideTransaction() {
        AtomicBoolean plannerCalledInTransaction = new AtomicBoolean(true);
        AtomicInteger offset = new AtomicInteger();
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willAnswer(invocation -> {
                    plannerCalledInTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return plan(true, offset.getAndIncrement());
                });

        sessionService.createSession("1", createRequest());

        assertThat(plannerCalledInTransaction).isFalse();
    }

    @Test
    @DisplayName("AI 코스 후보를 승격하면 실제 Course와 route point 복사본을 만든다")
    void promoteCandidateCreatesCourseAndRoutePoints() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true, 0), plan(true, 1), plan(true, 2));
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
                .willReturn(plan(true, 0), plan(true, 1), plan(true, 2));
        AiRouteGenerationSessionResponse session = sessionService.createSession("1", createRequest());

        assertThatThrownBy(() -> sessionService.getSession("2", session.sessionId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("AI 코스 생성 세션을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("이미 승격된 AI 코스 후보는 기존 courseId로 멱등 수렴한다")
    void promoteCandidateReturnsExistingCourseForRepeatedRequest() {
        given(aiRoutePlannerService.plan(eq("1"), any(AiRoutePlanRequest.class)))
                .willReturn(plan(true, 0), plan(true, 1), plan(true, 2));
        AiRouteGenerationSessionResponse session = sessionService.createSession("1", createRequest());
        Long candidateId = session.candidates().get(0).candidateId();
        PromoteAiRouteCandidateRequest request = new PromoteAiRouteCandidateRequest("남산 업힐 후보", "검수 후 저장", "PRIVATE");
        AiRoutePromotedCourseResponse first = sessionService.promoteCandidate("1", session.sessionId(), candidateId, request);

        AiRoutePromotedCourseResponse repeated = sessionService.promoteCandidate("1", session.sessionId(), candidateId, request);

        assertThat(repeated.courseId()).isEqualTo(first.courseId());
        assertThat(courseRepository.findAll()).hasSize(1);
        assertThat(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(first.courseId())).hasSize(2);
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

    private AiRoutePlanResponse plan(boolean aiGenerated, int offset) {
        BigDecimal candidateOffset = new BigDecimal("0.001").multiply(BigDecimal.valueOf(offset));
        return new AiRoutePlanResponse(
                "plan-" + offset,
                "READY",
                "남산 업힐 후보 " + offset,
                "HIGH",
                null,
                null,
                List.of(
                        new AiRoutePointResponse(BigDecimal.valueOf(37.4812), BigDecimal.valueOf(126.9527), "출발", BigDecimal.valueOf(42)),
                        new AiRoutePointResponse(
                                BigDecimal.valueOf(37.5512).add(candidateOffset),
                                BigDecimal.valueOf(126.9882),
                                "도착",
                                BigDecimal.valueOf(243)
                        )
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
