package com.bikeprojectminji.bikeback.routing.service;

import com.bikeprojectminji.bikeback.global.exception.InvalidRouteRequestException;
import com.bikeprojectminji.bikeback.global.exception.RetryableTooManyRequestsException;
import com.bikeprojectminji.bikeback.global.exception.RouteNotFoundException;
import com.bikeprojectminji.bikeback.global.exception.RoutingProviderUnavailableException;
import com.bikeprojectminji.bikeback.global.metrics.MeasuredOperation;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BicycleRoutingService {

    private final List<BicycleRoutingClient> routingClients;
    private final BicycleRouteQualityValidator qualityValidator;

    public BicycleRoutingService(List<BicycleRoutingClient> routingClients) {
        this(routingClients, new BicycleRouteQualityValidator());
    }

    @Autowired
    public BicycleRoutingService(List<BicycleRoutingClient> routingClients, BicycleRouteQualityValidator qualityValidator) {
        this.routingClients = routingClients;
        this.qualityValidator = qualityValidator;
    }

    @MeasuredOperation("routing.bicycle.route")
    public BicycleRoutePlan route(BicycleRouteRequest request) {
        validate(request);
        EnumSet<BicycleRoutingFailureCause> failureCauses = EnumSet.noneOf(BicycleRoutingFailureCause.class);
        int providerRetryAfterSeconds = 0;
        int quotaRetryAfterSeconds = 0;
        for (int index = 0; index < routingClients.size(); index++) {
            BicycleRoutingProviderResult result = routingClients.get(index).route(request);
            if ("SUCCESS".equals(result.status()) && !result.candidates().isEmpty()) {
                BicycleRouteQuality quality = qualityValidator.validate(result.candidates().get(0));
                if (!quality.usable()) {
                    failureCauses.add(BicycleRoutingFailureCause.QUALITY_REJECTED);
                    continue;
                }
                boolean fallbackUsed = index > 0 || result.fallbackUsed();
                return new BicycleRoutePlan(
                        fallbackUsed ? "FALLBACK_USED" : "SUCCESS",
                        result.provider(),
                        fallbackUsed,
                        fallbackUsed ? "기본 provider 실패 후 fallback 경로 후보를 찾았습니다." : "자전거 경로 후보를 찾았습니다.",
                        result.candidates(),
                        quality.status(),
                        quality.message(),
                        fallbackReason(index, result, failureCauses)
                );
            }
            failureCauses.add(result.failureCause() == null
                    ? BicycleRoutingFailureCause.NO_ROUTE
                    : result.failureCause());
            if (result.retryAfterSeconds() != null) {
                if (result.failureCause() == BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE) {
                    providerRetryAfterSeconds = Math.max(providerRetryAfterSeconds, result.retryAfterSeconds());
                }
                if (result.failureCause() == BicycleRoutingFailureCause.QUOTA_EXCEEDED) {
                    quotaRetryAfterSeconds = Math.max(quotaRetryAfterSeconds, result.retryAfterSeconds());
                }
            }
        }
        throw classifiedFailure(failureCauses, providerRetryAfterSeconds, quotaRetryAfterSeconds);
    }

    private RuntimeException classifiedFailure(
            EnumSet<BicycleRoutingFailureCause> causes,
            int providerRetryAfterSeconds,
            int quotaRetryAfterSeconds
    ) {
        if (causes.contains(BicycleRoutingFailureCause.NO_ROUTE)) {
            return new RouteNotFoundException("요청 조건을 충족하는 자전거 경로를 찾지 못했습니다. 조건을 완화해 주세요.");
        }
        if (causes.contains(BicycleRoutingFailureCause.QUALITY_REJECTED)) {
            return new RouteNotFoundException("경로 후보가 안전·품질 기준을 충족하지 못했습니다. 조건을 완화해 주세요.");
        }
        if (causes.contains(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE)) {
            return new RoutingProviderUnavailableException(
                    "자전거 경로 provider가 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요.",
                    providerRetryAfterSeconds > 0 ? providerRetryAfterSeconds : 3
            );
        }
        if (causes.contains(BicycleRoutingFailureCause.QUOTA_EXCEEDED)) {
            return new RetryableTooManyRequestsException(
                    "라우팅 provider 요청 한도에 도달했습니다. 잠시 후 다시 시도해 주세요.",
                    "ROUTING_QUOTA_EXCEEDED",
                    quotaRetryAfterSeconds > 0 ? quotaRetryAfterSeconds : 60
            );
        }
        return new RoutingProviderUnavailableException(
                "자전거 경로 provider가 일시적으로 불안정합니다. 잠시 후 다시 시도해 주세요.",
                providerRetryAfterSeconds > 0 ? providerRetryAfterSeconds : 3
        );
    }

    private String fallbackReason(
            int providerIndex,
            BicycleRoutingProviderResult result,
            EnumSet<BicycleRoutingFailureCause> previousCauses
    ) {
        if (result.fallbackReason() != null && !result.fallbackReason().isBlank()) {
            return result.fallbackReason();
        }
        if (providerIndex > 0) {
            if (previousCauses.contains(BicycleRoutingFailureCause.NO_ROUTE)) {
                return "primary provider 경로 없음 후 fallback provider 사용";
            }
            if (previousCauses.contains(BicycleRoutingFailureCause.QUALITY_REJECTED)) {
                return "primary provider 경로 품질 기준 탈락 후 fallback provider 사용";
            }
            if (previousCauses.contains(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE)) {
                return "primary provider 장애 후 fallback provider 사용";
            }
            if (previousCauses.contains(BicycleRoutingFailureCause.QUOTA_EXCEEDED)) {
                return "primary provider quota 제한 후 fallback provider 사용";
            }
            return "primary provider 실패 후 fallback provider 사용";
        }
        return null;
    }

    private void validate(BicycleRouteRequest request) {
        if (request == null) {
            throw new InvalidRouteRequestException("자전거 경로 요청이 필요합니다.");
        }
        validateCoordinate(request.originLat(), request.originLon(), "출발");
        validateCoordinate(request.destinationLat(), request.destinationLon(), "도착");
    }

    private void validateCoordinate(BigDecimal lat, BigDecimal lon, String label) {
        if (lat == null || lon == null) {
            throw new InvalidRouteRequestException(label + " 좌표가 필요합니다.");
        }
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new InvalidRouteRequestException(label + " lat는 -90 이상 90 이하여야 합니다.");
        }
        if (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new InvalidRouteRequestException(label + " lon은 -180 이상 180 이하여야 합니다.");
        }
    }
}
