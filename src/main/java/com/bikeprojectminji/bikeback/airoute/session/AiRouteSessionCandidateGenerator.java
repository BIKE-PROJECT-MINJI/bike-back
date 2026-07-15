package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
import com.bikeprojectminji.bikeback.routing.service.ProviderCallBudget;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class AiRouteSessionCandidateGenerator {

    static final int MAX_CANDIDATES = 3;
    private static final List<String> ALTERNATIVE_RIDE_STYLES = List.of(
            "BIKE_PATH_FIRST",
            "SCENERY_FIRST",
            "BALANCED"
    );

    private final AiRoutePlannerService plannerService;

    AiRouteSessionCandidateGenerator(AiRoutePlannerService plannerService) {
        this.plannerService = plannerService;
    }

    AiRouteSessionCandidateGeneration generate(String subject, AiRoutePlanRequest request) {
        List<AiRoutePlanResponse> plans = new ArrayList<>(MAX_CANDIDATES);
        Set<String> signatures = new LinkedHashSet<>();
        int noRouteCount = 0;
        int providerUnavailableCount = 0;
        int quotaExceededCount = 0;
        int duplicateCount = 0;
        int retryAfterSeconds = 0;
        int quotaRetryAfterSeconds = 0;
        ProviderCallBudget providerCallBudget = new ProviderCallBudget(MAX_CANDIDATES);

        List<AiRoutePlanRequest> attempts = candidateRequests(request);
        for (AiRoutePlanRequest attempt : attempts) {
            try {
                AiRoutePlanResponse plan = plannerService.plan(subject, attempt, providerCallBudget);
                if (plan.routePoints() == null || plan.routePoints().isEmpty()) {
                    noRouteCount++;
                    continue;
                }
                if (signatures.add(signature(plan))) {
                    plans.add(plan);
                } else {
                    duplicateCount++;
                }
            } catch (RouteNotFoundException exception) {
                noRouteCount++;
            } catch (RoutingProviderUnavailableException exception) {
                providerUnavailableCount++;
                retryAfterSeconds = Math.max(retryAfterSeconds, exception.getRetryAfterSeconds());
            } catch (RetryableTooManyRequestsException exception) {
                quotaExceededCount++;
                quotaRetryAfterSeconds = Math.max(quotaRetryAfterSeconds, exception.getRetryAfterSeconds());
            }
        }

        if (plans.isEmpty()) {
            if (quotaExceededCount > 0) {
                throw new RetryableTooManyRequestsException(
                        "라우팅 provider 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.",
                        "ROUTING_QUOTA_EXCEEDED",
                        quotaRetryAfterSeconds > 0 ? quotaRetryAfterSeconds : 60
                );
            }
            if (providerUnavailableCount > 0) {
                throw new RoutingProviderUnavailableException(
                        "자전거 경로 provider가 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요.",
                        retryAfterSeconds > 0 ? retryAfterSeconds : 3
                );
            }
            throw new RouteNotFoundException("요청 조건을 충족하는 AI 코스 후보를 찾지 못했습니다. 조건을 완화해 주세요.");
        }

        return new AiRouteSessionCandidateGeneration(
                List.copyOf(plans),
                attempts.size(),
                noRouteCount,
                providerUnavailableCount,
                quotaExceededCount,
                duplicateCount
        );
    }

    private List<AiRoutePlanRequest> candidateRequests(AiRoutePlanRequest request) {
        LinkedHashSet<String> rideStyles = new LinkedHashSet<>();
        rideStyles.add(normalizeRideStyle(request.rideStyle()));
        rideStyles.addAll(ALTERNATIVE_RIDE_STYLES);
        return rideStyles.stream()
                .limit(MAX_CANDIDATES)
                .map(rideStyle -> withRideStyle(request, rideStyle))
                .toList();
    }

    private String normalizeRideStyle(String rideStyle) {
        return rideStyle == null || rideStyle.isBlank() ? "BALANCED" : rideStyle;
    }

    private AiRoutePlanRequest withRideStyle(AiRoutePlanRequest request, String rideStyle) {
        return new AiRoutePlanRequest(
                request.lat(),
                request.lon(),
                request.destinationLat(),
                request.destinationLon(),
                request.destinationLabel(),
                rideStyle,
                request.elevationPreference(),
                request.textIntent()
        );
    }

    private String signature(AiRoutePlanResponse plan) {
        return routeSignature(plan.routePoints());
    }

    private String routeSignature(List<AiRoutePointResponse> routePoints) {
        StringBuilder signature = new StringBuilder();
        for (AiRoutePointResponse point : routePoints) {
            if (!signature.isEmpty()) {
                signature.append(';');
            }
            signature.append(point.lat().stripTrailingZeros().toPlainString())
                    .append(',')
                    .append(point.lon().stripTrailingZeros().toPlainString());
        }
        return signature.toString();
    }
}
