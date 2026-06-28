package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteElevationSummaryResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteRoutingMetadataResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.airoute.dto.RecommendationScoreResponse;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Primary
@Conditional(NonBlankAiRouteWorkerBaseUrlCondition.class)
public class HttpAiRouteWorkerClient implements AiRouteWorkerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiRouteWorkerClient.class);

    private final RestClient restClient;
    private final BikeMetricsRecorder bikeMetricsRecorder;

    public HttpAiRouteWorkerClient(String baseUrl) {
        this(baseUrl, new BikeMetricsRecorder(io.micrometer.core.instrument.Metrics.globalRegistry));
    }

    @Autowired
    public HttpAiRouteWorkerClient(
            @Value("${ai-route.worker.base-url}") String baseUrl,
            BikeMetricsRecorder bikeMetricsRecorder
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.bikeMetricsRecorder = bikeMetricsRecorder;
    }

    @Override
    public String provider() {
        return "HTTP_AI_ROUTE_WORKER";
    }

    @Override
    public Optional<AiRoutePlanResponse> plan(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            AiRoutePlanResponse fallbackPlan
    ) {
        long startedAtNanos = System.nanoTime();
        String outcome = "success";
        try {
            AiRoutePlanResponse response = restClient.post()
                    .uri("/v1/ai-routes/plan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(WorkerRoutePlanRequest.from(request, context, fallbackPlan))
                    .retrieve()
                    .body(AiRoutePlanResponse.class);
            return Optional.ofNullable(response);
        } catch (HttpStatusCodeException exception) {
            outcome = "http_" + exception.getStatusCode().value();
            // AI worker는 보조 설명 생성자다. 장애 시 fallback은 유지하되 운영자가 원인을 볼 수 있게 남긴다.
            log.warn(
                    "ai_route_worker_failed reason=http_status status={} endpoint=/v1/ai-routes/plan",
                    exception.getStatusCode().value()
            );
            return Optional.empty();
        } catch (RestClientException exception) {
            outcome = "rest_client_exception";
            // 네트워크/타임아웃도 사용자 응답은 backend fallback으로 보호하고, 로그에서만 추적한다.
            log.warn(
                    "ai_route_worker_failed reason=rest_client_exception exception={} endpoint=/v1/ai-routes/plan",
                    exception.getClass().getSimpleName()
            );
            return Optional.empty();
        } finally {
            bikeMetricsRecorder.recordProviderCall(
                    "AI_ROUTE_WORKER",
                    "plan",
                    outcome,
                    Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    private record WorkerRoutePlanRequest(
            BigDecimal lat,
            BigDecimal lon,
            BigDecimal destinationLat,
            BigDecimal destinationLon,
            String destinationLabel,
            String rideStyle,
            String elevationPreference,
            String textIntent,
            CurrentWeatherResponse weather,
            String constructionSummary,
            String roadSurfaceSummary,
            int recommendationScore,
            RecommendationScoreResponse scoreBreakdown,
            AiRouteElevationSummaryResponse elevationSummary,
            AiRouteRoutingMetadataResponse routingMetadata,
            List<ProviderEvidenceBadgeResponse> evidenceBadges,
            String preferenceSummary,
            String elevationStatus,
            String sceneryEvidenceStatus,
            AiRoutePlanResponse fallbackPlan
    ) {

        private static WorkerRoutePlanRequest from(
                AiRoutePlanRequest request,
                AiRouteConditionContext context,
                AiRoutePlanResponse fallbackPlan
        ) {
            return new WorkerRoutePlanRequest(
                    request.lat(),
                    request.lon(),
                    request.destinationLat(),
                    request.destinationLon(),
                    request.destinationLabel(),
                    request.rideStyle(),
                    request.elevationPreference(),
                    request.textIntent(),
                    context.weather().orElse(null),
                    context.constructionSummary(),
                    context.roadSurfaceSummary(),
                    fallbackPlan.recommendationScore(),
                    fallbackPlan.scoreBreakdown(),
                    fallbackPlan.elevationSummary(),
                    fallbackPlan.routingMetadata(),
                    fallbackPlan.evidenceBadges(),
                    fallbackPlan.preferenceSummary(),
                    fallbackPlan.elevationStatus(),
                    fallbackPlan.sceneryEvidenceStatus(),
                    fallbackPlan
            );
        }
    }
}
