package com.bikeprojectminji.bikeback.airoute.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
import com.bikeprojectminji.bikeback.routing.service.ProviderCallBudget;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiRouteSessionCandidateGeneratorTest {

    private AiRoutePlannerService plannerService;
    private AiRouteSessionCandidateGenerator generator;

    @BeforeEach
    void setUp() {
        plannerService = mock(AiRoutePlannerService.class);
        generator = new AiRouteSessionCandidateGenerator(plannerService);
    }

    @Test
    @DisplayName("세션 하나는 서로 다른 후보를 세 개까지만 생성한다")
    void generatesAtMostThreeDistinctCandidates() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willReturn(plan("a", 82, "빠른 경로", 0), plan("b", 87, "자전거도로 경로", 1), plan("c", 79, "경치 경로", 2));

        AiRouteSessionCandidateGeneration result = generator.generate("1", request());

        assertThat(result.plans()).extracting(AiRoutePlanResponse::planId)
                .containsExactly("a", "b", "c");
        assertThat(result.partial()).isFalse();
        assertThat(result.attemptCount()).isEqualTo(3);
        ArgumentCaptor<AiRoutePlanRequest> requestCaptor = ArgumentCaptor.forClass(AiRoutePlanRequest.class);
        ArgumentCaptor<ProviderCallBudget> budgetCaptor = ArgumentCaptor.forClass(ProviderCallBudget.class);
        verify(plannerService, times(3)).plan(eq("1"), requestCaptor.capture(), budgetCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(AiRoutePlanRequest::rideStyle)
                .containsExactly("SCENERY_FIRST", "BIKE_PATH_FIRST", "BALANCED");
        assertThat(budgetCaptor.getAllValues()).allMatch(budget -> budget == budgetCaptor.getValue());
    }

    @Test
    @DisplayName("후보 두 개 성공 뒤 경로 없음이면 PARTIAL 결과를 반환한다")
    void returnsPartialWhenTwoCandidatesSucceedAndOneHasNoRoute() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willReturn(plan("a", 82, "빠른 경로", 0), plan("b", 87, "자전거도로 경로", 1))
                .willThrow(new RouteNotFoundException("no route"));

        AiRouteSessionCandidateGeneration result = generator.generate("1", request());

        assertThat(result.plans()).hasSize(2);
        assertThat(result.partial()).isTrue();
        assertThat(result.fallbackReason()).contains("NO_ROUTE=1");
        verify(plannerService, times(3)).plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class));
    }

    @Test
    @DisplayName("provider가 정상이나 세 시도 모두 경로가 없으면 422 원인 예외를 보존한다")
    void throwsRouteNotFoundWhenNoAttemptReturnsRoute() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willThrow(new RouteNotFoundException("no route"));

        assertThatThrownBy(() -> generator.generate("1", request()))
                .isInstanceOf(RouteNotFoundException.class);
        verify(plannerService, times(3)).plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class));
    }

    @Test
    @DisplayName("세 시도 모두 provider 장애면 retry metadata를 가진 503 원인 예외를 보존한다")
    void throwsProviderUnavailableWhenEveryAttemptFails() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willThrow(new RoutingProviderUnavailableException("down", 7));

        assertThatThrownBy(() -> generator.generate("1", request()))
                .isInstanceOfSatisfying(RoutingProviderUnavailableException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isEqualTo(7));
        verify(plannerService, times(3)).plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class));
    }

    @Test
    @DisplayName("경로 좌표가 같으면 점수와 설명이 달라도 중복 저장하지 않는다")
    void deduplicatesEquivalentCandidates() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willReturn(
                        plan("first", 82, "빠른 경로", 0),
                        plan("second", 91, "경치 좋은 경로", 0),
                        plan("third", 75, "자전거도로 경로", 0)
                );

        AiRouteSessionCandidateGeneration result = generator.generate("1", request());

        assertThat(result.plans()).hasSize(1);
        assertThat(result.partial()).isTrue();
        assertThat(result.fallbackReason()).contains("DUPLICATE=2");
    }

    @Test
    @DisplayName("후보 성공 뒤 quota가 발생하면 성공 후보를 PARTIAL로 보존한다")
    void preservesSuccessfulCandidatesWhenLaterAttemptHitsQuota() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willReturn(plan("first", 82, "빠른 경로", 0))
                .willThrow(new RetryableTooManyRequestsException("quota", "ROUTING_QUOTA_EXCEEDED", 17));

        AiRouteSessionCandidateGeneration result = generator.generate("1", request());

        assertThat(result.plans()).hasSize(1);
        assertThat(result.fallbackReason()).contains("QUOTA_EXCEEDED=2");
    }

    @Test
    @DisplayName("모든 시도가 quota로 막히면 가장 긴 Retry-After를 가진 429를 반환한다")
    void throwsQuotaWhenNoCandidateSucceeds() {
        given(plannerService.plan(eq("1"), any(AiRoutePlanRequest.class), any(ProviderCallBudget.class)))
                .willThrow(
                        new RetryableTooManyRequestsException("quota", "ROUTING_QUOTA_EXCEEDED", 7),
                        new RouteNotFoundException("no route"),
                        new RoutingProviderUnavailableException("down", 3)
                );

        assertThatThrownBy(() -> generator.generate("1", request()))
                .isInstanceOfSatisfying(RetryableTooManyRequestsException.class,
                        exception -> assertThat(exception.getRetryAfterSeconds()).isEqualTo(7));
    }

    private AiRoutePlanRequest request() {
        return new AiRoutePlanRequest(
                new BigDecimal("37.5000"),
                new BigDecimal("127.0000"),
                new BigDecimal("37.5100"),
                new BigDecimal("127.0100"),
                "synthetic",
                "SCENERY_FIRST",
                "FLAT_FIRST",
                "강변 평지"
        );
    }

    private AiRoutePlanResponse plan(String planId, int score, String summary, int offset) {
        BigDecimal delta = new BigDecimal("0.001").multiply(BigDecimal.valueOf(offset));
        return new AiRoutePlanResponse(
                planId,
                "READY",
                summary,
                "HIGH",
                null,
                null,
                List.of(
                        new AiRoutePointResponse(new BigDecimal("37.5000"), new BigDecimal("127.0000"), "start", null),
                        new AiRoutePointResponse(new BigDecimal("37.5100").add(delta), new BigDecimal("127.0100"), "end", null)
                ),
                List.of(),
                List.of(),
                score,
                null,
                null,
                List.of(),
                true
        );
    }
}
