package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutePoint;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingClient;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "routing.bicycle", name = "provider", havingValue = "kakao")
public class KakaoMobilityBicycleRoutingClient implements BicycleRoutingClient {

    private static final String PROVIDER = "KAKAO_MOBILITY";

    private final RestClient restClient;
    private final String restApiKey;
    private final BikeMetricsRecorder bikeMetricsRecorder;

    public KakaoMobilityBicycleRoutingClient(String baseUrl, String restApiKey) {
        this(baseUrl, restApiKey, null);
    }

    @Autowired
    public KakaoMobilityBicycleRoutingClient(
            @Value("${routing.bicycle.kakao.base-url:https://apis-navi.kakaomobility.com}") String baseUrl,
            @Value("${routing.bicycle.kakao.rest-api-key:}") String restApiKey,
            BikeMetricsRecorder bikeMetricsRecorder
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.restApiKey = restApiKey;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
    }

    @Override
    public BicycleRoutingProviderResult route(BicycleRouteRequest request) {
        if (restApiKey == null || restApiKey.isBlank()) {
            return providerFailure("missing_api_key");
        }
        try {
            KakaoBicycleDirectionsResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/affiliate/bicycle/v1/directions")
                            .queryParam("origin", coordinate(request.originLon(), request.originLat()))
                            .queryParam("destination", coordinate(request.destinationLon(), request.destinationLat()))
                            .queryParam("priority", priorityFor(request.preference()))
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(KakaoBicycleDirectionsResponse.class);
            if (response == null || response.routes() == null || response.routes().isEmpty()) {
                return providerFailure("empty_response");
            }
            return BicycleRoutingProviderResult.success(PROVIDER, response.routes().stream()
                    .limit(3)
                    .map(route -> route.toCandidate(request.preference()))
                    .toList());
        } catch (HttpStatusCodeException exception) {
            return providerFailure(reasonForStatus(exception.getStatusCode()));
        } catch (RestClientException exception) {
            return providerFailure("rest_client_exception");
        }
    }

    private BicycleRoutingProviderResult providerFailure(String reason) {
        if (bikeMetricsRecorder != null) {
            bikeMetricsRecorder.recordRoutingProviderFailure(PROVIDER, reason);
        }
        return BicycleRoutingProviderResult.providerFailure(PROVIDER);
    }

    private String coordinate(BigDecimal lon, BigDecimal lat) {
        return lon.stripTrailingZeros().toPlainString() + "," + lat.stripTrailingZeros().toPlainString();
    }

    private String priorityFor(String preference) {
        if ("BIKE_PATH_FIRST".equals(preference)) {
            return "BIKE_ROAD";
        }
        if ("SHORTEST".equals(preference)) {
            return "DISTANCE";
        }
        return "BIKE_ROAD";
    }

    private String reasonForStatus(HttpStatusCode statusCode) {
        if (statusCode.value() == 429) {
            return "http_429";
        }
        if (statusCode.is5xxServerError()) {
            return "http_5xx";
        }
        return "http_4xx";
    }

    private record KakaoBicycleDirectionsResponse(List<KakaoRoute> routes) {
    }

    private record KakaoRoute(KakaoSummary summary, List<KakaoSection> sections) {

        private BicycleRouteCandidate toCandidate(String preference) {
            List<BicycleRoutePoint> points = new ArrayList<>();
            if (sections != null) {
                for (KakaoSection section : sections) {
                    if (section.roads() == null) {
                        continue;
                    }
                    for (KakaoRoad road : section.roads()) {
                        points.addAll(road.toPoints());
                    }
                }
            }
            return new BicycleRouteCandidate(
                    routeTypeFor(preference),
                    summary == null ? 0 : summary.distance(),
                    summary == null ? 0 : summary.duration(),
                    points,
                    "Kakao Mobility 자전거 경로 기준",
                    "BIKE_PATH_FIRST".equals(preference) ? 90 : 82,
                    "SCENERY_FIRST".equals(preference) ? 84 : 70
            );
        }

        private String routeTypeFor(String preference) {
            if ("SCENERY_FIRST".equals(preference)) {
                return "SCENIC";
            }
            if ("BIKE_PATH_FIRST".equals(preference)) {
                return "BIKE_PATH";
            }
            return "RECOMMENDED";
        }
    }

    private record KakaoSummary(int distance, int duration) {
    }

    private record KakaoSection(List<KakaoRoad> roads) {
    }

    private record KakaoRoad(String name, List<BigDecimal> vertexes) {

        private List<BicycleRoutePoint> toPoints() {
            if (vertexes == null || vertexes.size() < 2) {
                return List.of();
            }
            List<BicycleRoutePoint> points = new ArrayList<>();
            for (int index = 0; index + 1 < vertexes.size(); index += 2) {
                points.add(new BicycleRoutePoint(vertexes.get(index + 1), vertexes.get(index), name));
            }
            return points;
        }
    }
}
