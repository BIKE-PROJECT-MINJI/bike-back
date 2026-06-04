package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePlan;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingService;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AiRoutePlannerService {

    private final CurrentWeatherLookup currentWeatherLookup;
    private final AiRouteWorkerClient aiRouteWorkerClient;
    private final AiRoutePlanComposer aiRoutePlanComposer;
    private final BicycleRoutingService bicycleRoutingService;
    private final UserRoutePreferenceProvider userRoutePreferenceProvider;

    public AiRoutePlannerService(
            CurrentWeatherLookup currentWeatherLookup,
            AiRouteWorkerClient aiRouteWorkerClient,
            AiRoutePlanComposer aiRoutePlanComposer,
            BicycleRoutingService bicycleRoutingService,
            UserRoutePreferenceProvider userRoutePreferenceProvider
    ) {
        this.currentWeatherLookup = currentWeatherLookup;
        this.aiRouteWorkerClient = aiRouteWorkerClient;
        this.aiRoutePlanComposer = aiRoutePlanComposer;
        this.bicycleRoutingService = bicycleRoutingService;
        this.userRoutePreferenceProvider = userRoutePreferenceProvider;
    }

    public AiRoutePlanResponse plan(AiRoutePlanRequest request) {
        return plan(null, request);
    }

    public AiRoutePlanResponse plan(String subject, AiRoutePlanRequest request) {
        validate(request);
        AiRoutePlanRequest resolvedRequest = applyDefaultRideStyle(subject, request);
        AiRouteConditionContext context = new AiRouteConditionContext(
                currentWeatherLookup.find(resolvedRequest.lat(), resolvedRequest.lon()),
                "공사/통제 데이터 provider가 아직 연결되지 않아 안전 우선으로 표시합니다.",
                "OSM surface/smoothness 태그와 외부 노면 provider 연동 전까지는 미확인으로 표시합니다."
        );
        AiRoutePlanResponse basePlan = composeRouteCandidatePlan(resolvedRequest, context)
                .orElseGet(() -> aiRoutePlanComposer.composeFallback(resolvedRequest, context));
        return aiRouteWorkerClient.plan(resolvedRequest, context, basePlan)
                .orElse(basePlan);
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
                        rideStyle
                ))
                .orElse(request);
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
                request.rideStyle()
        ));
        if (routePlan.candidates().isEmpty()) {
            throw new BadRequestException("GraphHopper 자전거 경로를 생성할 수 없습니다. self-host 또는 hosted GraphHopper 설정을 확인하세요.");
        }
        return Optional.of(aiRoutePlanComposer.composeWithRouteCandidate(
                request,
                context,
                routePlan.candidates().get(0)
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
