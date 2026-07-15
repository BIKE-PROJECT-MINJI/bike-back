package com.bikeprojectminji.bikeback.routing.infrastructure;

import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteCandidate;
import com.bikeprojectminji.bikeback.routing.service.BicycleRouteRequest;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingClient;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingFailureCause;
import com.bikeprojectminji.bikeback.routing.service.BicycleRoutingProviderResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
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
        EnumSet<BicycleRoutingFailureCause> failureCauses = EnumSet.noneOf(BicycleRoutingFailureCause.class);
        int providerRetryAfterSeconds = 0;
        int quotaRetryAfterSeconds = 0;
        providerEndpoints:
        for (int baseUrlIndex = 0; baseUrlIndex < baseUrls.size(); baseUrlIndex++) {
            String baseUrl = baseUrls.get(baseUrlIndex);
            int attemptsForEndpoint = baseUrls.size() > 1 ? 1 : retryMaxAttempts;
            for (int attempt = 0; attempt < attemptsForEndpoint; attempt++) {
                if (!acquireCallBudget(request)) {
                    failureCauses.add(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE);
                    providerRetryAfterSeconds = Math.max(providerRetryAfterSeconds, 3);
                    recordFailure("call_budget_exhausted");
                    break providerEndpoints;
                }
                lastResult = routeOnce(baseUrl, request);
                if ("SUCCESS".equals(lastResult.status())) {
                    if (baseUrlIndex > 0) {
                        return BicycleRoutingProviderResult.successWithFallback(
                                PROVIDER,
                                lastResult.candidates(),
                                fallbackReason(failureCauses)
                        );
                    }
                    return lastResult;
                }
                if (lastResult.failureCause() != null) {
                    failureCauses.add(lastResult.failureCause());
                }
                if (lastResult.retryAfterSeconds() != null) {
                    if (lastResult.failureCause() == BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE) {
                        providerRetryAfterSeconds = Math.max(providerRetryAfterSeconds, lastResult.retryAfterSeconds());
                    }
                    if (lastResult.failureCause() == BicycleRoutingFailureCause.QUOTA_EXCEEDED) {
                        quotaRetryAfterSeconds = Math.max(quotaRetryAfterSeconds, lastResult.retryAfterSeconds());
                    }
                }
                if (!lastResult.sameEndpointRetryable()) {
                    break;
                }
            }
        }
        return aggregateFailure(failureCauses, providerRetryAfterSeconds, quotaRetryAfterSeconds, lastResult);
    }

    private boolean acquireCallBudget(BicycleRouteRequest request) {
        return request.providerCallBudget() == null || request.providerCallBudget().tryAcquire();
    }

    private BicycleRoutingProviderResult routeOnce(String baseUrl, BicycleRouteRequest request) {
        long startedAtNanos = System.nanoTime();
        String outcome = "success";
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
                        Optional.ofNullable(request.routePreference().graphHopperCustomModelJson())
                                .ifPresent(customModel -> builder.queryParam("custom_model", customModel));
                        if (!apiKey.isBlank()) {
                            builder.queryParam("key", apiKey);
                        }
                        return builder.build().toUri();
                    })
                    .retrieve()
                    .body(GraphHopperRouteResponse.class);
            if (response == null || response.paths() == null || response.paths().isEmpty()) {
                outcome = "empty_response";
                return noRoute("empty_response");
            }
            List<BicycleRouteCandidate> candidates = response.paths().stream()
                    .limit(3)
                    .map(path -> path.toCandidate(request.preference(), evidenceMapper))
                    .filter(candidate -> !candidate.polyline().isEmpty())
                    .toList();
            if (candidates.isEmpty()) {
                outcome = "invalid_response";
                return providerFailure("invalid_response");
            }
            return BicycleRoutingProviderResult.success(PROVIDER, candidates);
        } catch (HttpStatusCodeException exception) {
            outcome = reasonForStatus(exception.getStatusCode().value());
            return failureForHttpStatus(exception, outcome);
        } catch (RestClientException exception) {
            outcome = "rest_client_exception";
            return retryableProviderFailure(outcome, 3);
        } catch (IllegalArgumentException exception) {
            outcome = "illegal_argument";
            return providerContractFailure(outcome);
        } finally {
            if (bikeMetricsRecorder != null) {
                bikeMetricsRecorder.recordProviderCall(
                        PROVIDER,
                        "route",
                        outcome,
                        Duration.ofNanos(System.nanoTime() - startedAtNanos)
                );
            }
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
        recordFailure(reason);
        return BicycleRoutingProviderResult.providerFailure(PROVIDER);
    }

    private BicycleRoutingProviderResult retryableProviderFailure(String reason, int retryAfterSeconds) {
        recordFailure(reason);
        return BicycleRoutingProviderResult.providerFailure(PROVIDER, retryAfterSeconds);
    }

    private BicycleRoutingProviderResult noRoute(String reason) {
        return BicycleRoutingProviderResult.noRoute(PROVIDER);
    }

    private BicycleRoutingProviderResult quotaExceeded(String reason, int retryAfterSeconds) {
        recordFailure(reason);
        return BicycleRoutingProviderResult.quotaExceeded(PROVIDER, retryAfterSeconds);
    }

    private BicycleRoutingProviderResult failureForHttpStatus(HttpStatusCodeException exception, String reason) {
        int statusCode = exception.getStatusCode().value();
        if (statusCode == 429) {
            return quotaExceeded(reason, ProviderRetryAfterParser.secondsOrDefault(exception.getResponseHeaders(), 60));
        }
        if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return retryableProviderFailure(
                    reason,
                    ProviderRetryAfterParser.secondsOrDefault(exception.getResponseHeaders(), 3)
            );
        }
        return providerContractFailure(reason);
    }

    private BicycleRoutingProviderResult providerContractFailure(String reason) {
        return providerFailure("provider_contract_" + reason);
    }

    private void recordFailure(String reason) {
        if (bikeMetricsRecorder != null) {
            bikeMetricsRecorder.recordRoutingProviderFailure(PROVIDER, reason);
        }
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

    private BicycleRoutingProviderResult aggregateFailure(
            EnumSet<BicycleRoutingFailureCause> causes,
            int providerRetryAfterSeconds,
            int quotaRetryAfterSeconds,
            BicycleRoutingProviderResult lastResult
    ) {
        if (causes.contains(BicycleRoutingFailureCause.QUOTA_EXCEEDED)) {
            return BicycleRoutingProviderResult.quotaExceeded(
                    PROVIDER,
                    quotaRetryAfterSeconds > 0 ? quotaRetryAfterSeconds : 60
            );
        }
        if (causes.contains(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE)) {
            return providerRetryAfterSeconds > 0
                    ? BicycleRoutingProviderResult.providerFailure(PROVIDER, providerRetryAfterSeconds)
                    : BicycleRoutingProviderResult.providerFailure(PROVIDER);
        }
        if (causes.contains(BicycleRoutingFailureCause.NO_ROUTE)) {
            return BicycleRoutingProviderResult.noRoute(PROVIDER);
        }
        return lastResult;
    }

    private String fallbackReason(EnumSet<BicycleRoutingFailureCause> causes) {
        if (causes.contains(BicycleRoutingFailureCause.NO_ROUTE)) {
            return "self-host 경로 없음 후 hosted GraphHopper 사용";
        }
        if (causes.contains(BicycleRoutingFailureCause.PROVIDER_UNAVAILABLE)) {
            return "self-host provider 장애 후 hosted GraphHopper 사용";
        }
        if (causes.contains(BicycleRoutingFailureCause.QUOTA_EXCEEDED)) {
            return "self-host quota 제한 후 hosted GraphHopper 사용";
        }
        return "self-host 경로 품질 기준 탈락 후 hosted GraphHopper 사용";
    }

    private String coordinate(BigDecimal lat, BigDecimal lon) {
        return lat.stripTrailingZeros().toPlainString() + "," + lon.stripTrailingZeros().toPlainString();
    }
}
