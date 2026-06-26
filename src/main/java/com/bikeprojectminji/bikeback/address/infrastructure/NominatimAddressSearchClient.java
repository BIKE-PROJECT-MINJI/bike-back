package com.bikeprojectminji.bikeback.address.infrastructure;

import com.bikeprojectminji.bikeback.address.service.AddressCandidate;
import com.bikeprojectminji.bikeback.address.service.AddressSearchClient;
import com.bikeprojectminji.bikeback.address.service.AddressSearchProviderResult;
import com.bikeprojectminji.bikeback.address.service.AddressSearchQuery;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Order(10)
@ConditionalOnExpression("'${address.search.provider:kakao}' == 'kakao' || '${address.search.provider:kakao}' == 'nominatim'")
public class NominatimAddressSearchClient implements AddressSearchClient {

    private static final String PROVIDER = "NOMINATIM";

    private final RestClient restClient;
    private final String userAgent;

    public NominatimAddressSearchClient(
            @Value("${address.search.nominatim.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${address.search.nominatim.user-agent:BIKE-GAJA-local-prototype/1.0}") String userAgent
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(4));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.userAgent = userAgent;
    }

    @Override
    public AddressSearchProviderResult search(AddressSearchQuery query) {
        try {
            NominatimPlace[] response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search")
                            .queryParam("format", "jsonv2")
                            .queryParam("q", query.rawQuery())
                            .queryParam("countrycodes", "kr")
                            .queryParam("accept-language", "ko")
                            .queryParam("limit", query.size())
                            .build())
                    .header(HttpHeaders.USER_AGENT, userAgent)
                    .retrieve()
                    .body(NominatimPlace[].class);
            if (response == null || response.length == 0) {
                return AddressSearchProviderResult.empty(PROVIDER);
            }
            List<AddressCandidate> candidates = List.of(response).stream()
                    .map(NominatimPlace::toCandidate)
                    .toList();
            return AddressSearchProviderResult.success(PROVIDER, candidates);
        } catch (HttpStatusCodeException exception) {
            if (isRateLimited(exception.getStatusCode())) {
                return AddressSearchProviderResult.rateLimited(PROVIDER);
            }
            return AddressSearchProviderResult.providerFailure(PROVIDER);
        } catch (RestClientException | NumberFormatException exception) {
            return AddressSearchProviderResult.providerFailure(PROVIDER);
        }
    }

    private boolean isRateLimited(HttpStatusCode statusCode) {
        return statusCode.value() == 429;
    }

    private record NominatimPlace(
            Long place_id,
            String name,
            String display_name,
            String lat,
            String lon,
            String type
    ) {

        private AddressCandidate toCandidate() {
            return new AddressCandidate(
                    place_id == null ? display_name : String.valueOf(place_id),
                    firstNonBlank(name, firstAddressPart(display_name), "주소 후보"),
                    firstNonBlank(display_name, name, ""),
                    new BigDecimal(lat),
                    new BigDecimal(lon),
                    PROVIDER,
                    firstNonBlank(type, "PLACE"),
                    "MEDIUM"
            );
        }

        private static String firstAddressPart(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return value.split(",")[0].trim();
        }

        private static String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first;
        }

        private static String firstNonBlank(String first, String second, String fallback) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            return second == null || second.isBlank() ? fallback : second;
        }
    }
}
