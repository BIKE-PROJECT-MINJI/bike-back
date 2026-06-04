package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePoint;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingClient;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
@ConditionalOnProperty(prefix = "routing.bicycle.fake", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FakeBicycleRoutingClient implements BicycleRoutingClient {

    private static final String PROVIDER = "FAKE";

    @Override
    public BicycleRoutingProviderResult route(BicycleRouteRequest request) {
        return BicycleRoutingProviderResult.success(PROVIDER, List.of(
                candidate(request, "RECOMMENDED", 5200, 1420, "균형형 자전거 여행 후보", 78, 76),
                candidate(request, "SCENIC", 5900, 1660, "경치 좋은 완만한 우회 후보", 72, 90),
                candidate(request, "BIKE_PATH", 5500, 1500, "자전거도로 우선 후보", 92, 70)
        ));
    }

    private BicycleRouteCandidate candidate(
            BicycleRouteRequest request,
            String routeType,
            int distanceMeters,
            int durationSeconds,
            String evidenceSummary,
            int bikePathScore,
            int sceneryScore
    ) {
        return new BicycleRouteCandidate(
                routeType,
                distanceMeters,
                durationSeconds,
                polyline(request, routeType),
                evidenceSummary,
                bikePathScore,
                sceneryScore
        );
    }

    private List<BicycleRoutePoint> polyline(BicycleRouteRequest request, String routeType) {
        BigDecimal midLat = request.originLat().add(request.destinationLat()).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        BigDecimal midLon = request.originLon().add(request.destinationLon()).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
        BigDecimal offset = offsetFor(routeType);
        return List.of(
                new BicycleRoutePoint(request.originLat(), request.originLon(), "출발지"),
                new BicycleRoutePoint(midLat.add(offset), midLon.add(offset), evidenceLabel(routeType)),
                new BicycleRoutePoint(request.destinationLat(), request.destinationLon(), "도착지")
        );
    }

    private BigDecimal offsetFor(String routeType) {
        return switch (routeType) {
            case "SCENIC" -> BigDecimal.valueOf(0.004);
            case "BIKE_PATH" -> BigDecimal.valueOf(0.002);
            default -> BigDecimal.valueOf(0.001);
        };
    }

    private String evidenceLabel(String routeType) {
        return switch (routeType) {
            case "SCENIC" -> "전망 우회 구간";
            case "BIKE_PATH" -> "자전거도로 우선 구간";
            default -> "균형 추천 구간";
        };
    }
}
