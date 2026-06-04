package com.bikeprojectminji.bikeback.routing.service;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BicycleRoutingService {

    private final List<BicycleRoutingClient> routingClients;

    public BicycleRoutingService(List<BicycleRoutingClient> routingClients) {
        this.routingClients = routingClients;
    }

    public BicycleRoutePlan route(BicycleRouteRequest request) {
        validate(request);
        for (int index = 0; index < routingClients.size(); index++) {
            BicycleRoutingProviderResult result = routingClients.get(index).route(request);
            if ("SUCCESS".equals(result.status()) && !result.candidates().isEmpty()) {
                return new BicycleRoutePlan(
                        index == 0 ? "SUCCESS" : "FALLBACK_USED",
                        result.provider(),
                        index > 0,
                        index == 0 ? "자전거 경로 후보를 찾았습니다." : "기본 provider 실패 후 fallback 경로 후보를 찾았습니다.",
                        result.candidates()
                );
            }
        }
        return new BicycleRoutePlan(
                "ROUTING_FAILED",
                "NONE",
                routingClients.size() > 1,
                "자전거 경로 provider를 사용할 수 없습니다.",
                List.of()
        );
    }

    private void validate(BicycleRouteRequest request) {
        if (request == null) {
            throw new BadRequestException("자전거 경로 요청이 필요합니다.");
        }
        validateCoordinate(request.originLat(), request.originLon(), "출발");
        validateCoordinate(request.destinationLat(), request.destinationLon(), "도착");
    }

    private void validateCoordinate(BigDecimal lat, BigDecimal lon, String label) {
        if (lat == null || lon == null) {
            throw new BadRequestException(label + " 좌표가 필요합니다.");
        }
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BadRequestException(label + " lat는 -90 이상 90 이하여야 합니다.");
        }
        if (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new BadRequestException(label + " lon은 -180 이상 180 이하여야 합니다.");
        }
    }
}
