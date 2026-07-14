package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteTextPlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteWorkerMetadataResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePlan;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingService;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AiRoutePlannerService {

    private static final BigDecimal DEFAULT_DESTINATION_LAT_OFFSET = BigDecimal.valueOf(0.012);
    private static final BigDecimal DEFAULT_DESTINATION_LON_OFFSET = BigDecimal.valueOf(0.014);
    private static final String DEFAULT_CURRENT_LOCATION_DESTINATION_LABEL = "현재 위치 기반 추천 코스";

    private final CurrentWeatherLookup currentWeatherLookup;
    private final AiRouteWorkerClient aiRouteWorkerClient;
    private final AiRoutePlanComposer aiRoutePlanComposer;
    private final BicycleRoutingService bicycleRoutingService;
    private final UserRoutePreferenceProvider userRoutePreferenceProvider;
    private final AiRouteTextIntentResolver textIntentResolver;

    public AiRoutePlannerService(
            CurrentWeatherLookup currentWeatherLookup,
            AiRouteWorkerClient aiRouteWorkerClient,
            AiRoutePlanComposer aiRoutePlanComposer,
            BicycleRoutingService bicycleRoutingService,
            UserRoutePreferenceProvider userRoutePreferenceProvider,
            AiRouteTextIntentResolver textIntentResolver
    ) {
        this.currentWeatherLookup = currentWeatherLookup;
        this.aiRouteWorkerClient = aiRouteWorkerClient;
        this.aiRoutePlanComposer = aiRoutePlanComposer;
        this.bicycleRoutingService = bicycleRoutingService;
        this.userRoutePreferenceProvider = userRoutePreferenceProvider;
        this.textIntentResolver = textIntentResolver;
    }

    public AiRoutePlanResponse plan(AiRoutePlanRequest request) {
        return plan(null, request);
    }

    @MeasuredOperation("ai_route.plan")
    public AiRoutePlanResponse plan(String subject, AiRoutePlanRequest request) {
        validate(request);
        AiRoutePlanRequest resolvedRequest = applyDefaultDestination(applyDefaultRideStyle(subject, request));
        AiRouteConditionContext context = new AiRouteConditionContext(
                currentWeatherLookup.find(resolvedRequest.lat(), resolvedRequest.lon()),
                "공사/통제 데이터 provider가 아직 연결되지 않아 안전 우선으로 표시합니다.",
                "OSM surface/smoothness 태그와 외부 노면 provider 연동 전까지는 미확인으로 표시합니다."
        );
        AiRoutePlanResponse basePlan = composeRouteCandidatePlan(resolvedRequest, context)
                .orElseGet(() -> aiRoutePlanComposer.composeFallback(resolvedRequest, context));
        return aiRouteWorkerClient.plan(resolvedRequest, context, basePlan)
                .map(workerPlan -> mergeWorkerNarrative(basePlan, workerPlan))
                .orElseGet(() -> withWorkerFallbackMetadata(basePlan));
    }

    @MeasuredOperation("ai_route.plan_from_text")
    public AiRoutePlanResponse planFromText(String subject, AiRouteTextPlanRequest request) {
        validateTextRequest(request);
        AiRoutePlanRequest resolvedRequest = textIntentResolver.resolve(request);
        return plan(subject, resolvedRequest);
    }

    private AiRoutePlanRequest applyDefaultRideStyle(String subject, AiRoutePlanRequest request) {
        if (subject == null || request.rideStyle() != null && !request.rideStyle().isBlank()) {
            return request;
        }
        return userRoutePreferenceProvider.findDefaultRideStyle(subject)
                .map(rideStyle -> new AiRoutePlanRequest(
                        request.lat(),
                        request.lon(),
                        request.destinationLat(),
                        request.destinationLon(),
                        request.destinationLabel(),
                        rideStyle,
                        request.elevationPreference(),
                        request.textIntent()
                ))
                .orElse(request);
    }

    private AiRoutePlanRequest applyDefaultDestination(AiRoutePlanRequest request) {
        if (request.destinationLat() != null && request.destinationLon() != null) {
            return request;
        }
        return new AiRoutePlanRequest(
                request.lat(),
                request.lon(),
                request.lat().add(DEFAULT_DESTINATION_LAT_OFFSET),
                request.lon().add(DEFAULT_DESTINATION_LON_OFFSET),
                normalizeDestinationLabel(request.destinationLabel()),
                request.rideStyle(),
                request.elevationPreference(),
                request.textIntent()
        );
    }

    private String normalizeDestinationLabel(String destinationLabel) {
        if (destinationLabel == null || destinationLabel.isBlank()) {
            return DEFAULT_CURRENT_LOCATION_DESTINATION_LABEL;
        }
        return destinationLabel;
    }

    private Optional<AiRoutePlanResponse> composeRouteCandidatePlan(
            AiRoutePlanRequest request,
            AiRouteConditionContext context
    ) {
        if (request.destinationLat() == null || request.destinationLon() == null) {
            return Optional.empty();
        }
        BicycleRoutePlan routePlan = bicycleRoutingService.route(new BicycleRouteRequest(
                request.lat(),
                request.lon(),
                request.destinationLat(),
                request.destinationLon(),
                request.rideStyle(),
                request.elevationPreference(),
                request.textIntent()
        ));
        if (routePlan.candidates().isEmpty()) {
            throw new BadRequestException("GraphHopper 자전거 경로를 생성할 수 없습니다. self-host 또는 hosted GraphHopper 설정을 확인하세요.");
        }
        return Optional.of(aiRoutePlanComposer.composeWithRouteCandidate(
                request,
                context,
                routePlan.candidates().get(0),
                routePlan
        ));
    }

    private AiRoutePlanResponse mergeWorkerNarrative(
            AiRoutePlanResponse basePlan,
            AiRoutePlanResponse workerPlan
    ) {
        return new AiRoutePlanResponse(
                basePlan.planId(),
                basePlan.status(),
                nonBlankOrFallback(workerPlan.summary(), basePlan.summary()),
                basePlan.confidence(),
                basePlan.weather(),
                basePlan.wind(),
                basePlan.routePoints(),
                basePlan.risks(),
                basePlan.actions(),
                basePlan.recommendationScore(),
                basePlan.scoreBreakdown(),
                workerPlan.explanation() != null ? workerPlan.explanation() : basePlan.explanation(),
                basePlan.evidenceBadges(),
                true,
                basePlan.elevationSummary(),
                basePlan.routingMetadata(),
                new AiRouteWorkerMetadataResponse(aiRouteWorkerClient.provider(), false, null),
                basePlan.preferenceSummary(),
                basePlan.elevationStatus(),
                basePlan.sceneryEvidenceStatus()
        );
    }

    private String nonBlankOrFallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private AiRoutePlanResponse withWorkerFallbackMetadata(AiRoutePlanResponse plan) {
        return plan.withAiWorkerMetadata(new AiRouteWorkerMetadataResponse(
                aiRouteWorkerClient.provider(),
                true,
                aiRouteWorkerClient.fallbackReasonWhenEmpty()
        ));
    }

    private void validate(AiRoutePlanRequest request) {
        if (request == null) {
            throw new BadRequestException("AI 경로 요청이 필요합니다.");
        }
        validateCoordinate(request.lat(), request.lon(), "현재 위치");
        if (request.destinationLat() != null || request.destinationLon() != null) {
            validateCoordinate(request.destinationLat(), request.destinationLon(), "도착 위치");
        }
    }

    private void validateTextRequest(AiRouteTextPlanRequest request) {
        if (request == null) {
            throw new BadRequestException("텍스트 기반 AI 경로 요청이 필요합니다.");
        }
        validateCoordinate(request.lat(), request.lon(), "현재 위치");
        if (request.text() == null || request.text().isBlank()) {
            throw new BadRequestException("코스 생성 텍스트는 비어 있을 수 없습니다.");
        }
    }

    private void validateCoordinate(BigDecimal lat, BigDecimal lon, String label) {
        if (lat == null || lon == null) {
            throw new BadRequestException(label + " lat/lon이 필요합니다.");
        }
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BadRequestException(label + " lat는 -90 이상 90 이하여야 합니다.");
        }
        if (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new BadRequestException(label + " lon은 -180 이상 180 이하여야 합니다.");
        }
    }
}
