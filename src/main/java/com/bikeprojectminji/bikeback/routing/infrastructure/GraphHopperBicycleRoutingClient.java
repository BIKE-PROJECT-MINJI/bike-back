package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingClient;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "routing.bicycle", name = "provider", havingValue = "graphhopper")
public class GraphHopperBicycleRoutingClient implements BicycleRoutingClient {

    private static final String PROVIDER = "GRAPHHOPPER";

    private final RestClient restClient;
    private final List<String> baseUrls;
    private final String apiKey;
    private final int retryMaxAttempts;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final GraphHopperRouteEvidenceMapper evidenceMapper = new GraphHopperRouteEvidenceMapper();

    public GraphHopperBicycleRoutingClient(String baseUrl, String apiKey) {
        this(List.of(baseUrl), apiKey, 1, null);
    }

    public GraphHopperBicycleRoutingClient(String baseUrl, String apiKey, BikeMetricsRecorder bikeMetricsRecorder) {
        this(List.of(baseUrl), apiKey, 1, bikeMetricsRecorder);
    }

    GraphHopperBicycleRoutingClient(List<String> baseUrls, String apiKey, int retryMaxAttempts, BikeMetricsRecorder bikeMetricsRecorder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.baseUrls = normalizeBaseUrls(baseUrls);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.bikeMetricsRecorder = bikeMetricsRecorder;
    }

    @Autowired
    public GraphHopperBicycleRoutingClient(
            @Value("${routing.bicycle.graphhopper.base-url:http://127.0.0.1:8989}") String baseUrl,
            @Value("${routing.bicycle.graphhopper.hosted-base-url:}") String hostedBaseUrl,
            @Value("${routing.bicycle.graphhopper.api-key:}") String apiKey,
            @Value("${routing.bicycle.graphhopper.retry-max-attempts:2}") int retryMaxAttempts,
            BikeMetricsRecorder bikeMetricsRecorder
    ) {
        this(List.of(baseUrl, hostedBaseUrl), apiKey, retryMaxAttempts, bikeMetricsRecorder);
    }

    @Override
    public BicycleRoutingProviderResult route(BicycleRouteRequest request) {
        if (baseUrls.isEmpty()) {
            return providerFailure("missing_base_url");
        }
        BicycleRoutingProviderResult lastResult = BicycleRoutingProviderResult.providerFailure(PROVIDER);
        for (int baseUrlIndex = 0; baseUrlIndex < baseUrls.size(); baseUrlIndex++) {
            String baseUrl = baseUrls.get(baseUrlIndex);
            for (int attempt = 0; attempt < retryMaxAttempts; attempt++) {
                lastResult = routeOnce(baseUrl, request);
                if ("SUCCESS".equals(lastResult.status())) {
                    if (baseUrlIndex > 0) {
                        return BicycleRoutingProviderResult.successWithFallback(
                                PROVIDER,
                                lastResult.candidates(),
                                "self-host 실패 후 hosted GraphHopper 사용"
                        );
                    }
                    return lastResult;
                }
            }
        }
        return lastResult;
    }

    private BicycleRoutingProviderResult routeOnce(String baseUrl, BicycleRouteRequest request) {
        try {
            GraphHopperRouteResponse response = restClient.get()
                    .uri(ignored -> {
                        var builder = UriComponentsBuilder.fromUriString(baseUrl)
                                .path("/route")
                                .queryParam("point", coordinate(request.originLat(), request.originLon()))
                                .queryParam("point", coordinate(request.destinationLat(), request.destinationLon()))
                                .queryParam("profile", "bike")
                                .queryParam("points_encoded", "false")
                                .queryParam("elevation", "true")
                                .queryParam("locale", "ko")
                                .queryParam("details", "road_class")
                                .queryParam("details", "road_environment")
                                .queryParam("details", "surface")
                                .queryParam("details", "smoothness")
                                .queryParam("details", "bike_network")
                                .queryParam("details", "average_slope")
                                .queryParam("details", "max_slope");
                        if (!apiKey.isBlank()) {
                            builder.queryParam("key", apiKey);
                        }
                        return builder.build().toUri();
                    })
                    .retrieve()
                    .body(GraphHopperRouteResponse.class);
            if (response == null || response.paths() == null || response.paths().isEmpty()) {
                return providerFailure("empty_response");
            }
            List<BicycleRouteCandidate> candidates = response.paths().stream()
                    .limit(3)
                    .map(path -> path.toCandidate(request.preference(), evidenceMapper))
                    .filter(candidate -> !candidate.polyline().isEmpty())
                    .toList();
            if (candidates.isEmpty()) {
                return providerFailure("invalid_response");
            }
            return BicycleRoutingProviderResult.success(PROVIDER, candidates);
        } catch (HttpStatusCodeException exception) {
            return providerFailure(reasonForStatus(exception.getStatusCode().value()));
        } catch (RestClientException | IllegalArgumentException exception) {
            return providerFailure(exception instanceof IllegalArgumentException ? "illegal_argument" : "rest_client_exception");
        }
    }

    private List<String> normalizeBaseUrls(List<String> rawBaseUrls) {
        if (rawBaseUrls == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawBaseUrl : rawBaseUrls) {
            if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
                continue;
            }
            normalized.add(rawBaseUrl.strip().replaceAll("/+$", ""));
        }
        return List.copyOf(normalized);
    }

    private BicycleRoutingProviderResult providerFailure(String reason) {
        if (bikeMetricsRecorder != null) {
            bikeMetricsRecorder.recordRoutingProviderFailure(PROVIDER, reason);
        }
        return BicycleRoutingProviderResult.providerFailure(PROVIDER);
    }

    private String reasonForStatus(int statusCode) {
        if (statusCode == 429) {
            return "http_429";
        }
        if (statusCode >= 500) {
            return "http_5xx";
        }
        return "http_4xx";
    }

    private String coordinate(BigDecimal lat, BigDecimal lon) {
        return lat.stripTrailingZeros().toPlainString() + "," + lon.stripTrailingZeros().toPlainString();
    }
}
