package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteElevationSummaryResponse;
import com.bikeprojectminji.bikeback.airoute.dto.ProviderEvidenceBadgeResponse;
import com.bikeprojectminji.bikeback.airoute.dto.RecommendationScoreResponse;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Primary
@ConditionalOnProperty(name = "ai-route.worker.base-url")
public class HttpAiRouteWorkerClient implements AiRouteWorkerClient {

    private final RestClient restClient;

    public HttpAiRouteWorkerClient(@Value("${ai-route.worker.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public Optional<AiRoutePlanResponse> plan(
            AiRoutePlanRequest request,
            AiRouteConditionContext context,
            AiRoutePlanResponse fallbackPlan
    ) {
        try {
            AiRoutePlanResponse response = restClient.post()
                    .uri("/v1/ai-routes/plan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(WorkerRoutePlanRequest.from(request, context, fallbackPlan))
                    .retrieve()
                    .body(AiRoutePlanResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientException ignored) {
            return Optional.empty();
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
            List<ProviderEvidenceBadgeResponse> evidenceBadges,
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
                    fallbackPlan.evidenceBadges(),
                    fallbackPlan
            );
        }
    }
}
