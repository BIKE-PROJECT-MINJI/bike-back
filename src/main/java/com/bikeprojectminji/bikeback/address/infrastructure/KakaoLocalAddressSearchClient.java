package com.bikeprojectminji.bikeback.address.infrastructure;

import com.bikeprojectminji.bikeback.address.service.AddressCandidate;
import com.bikeprojectminji.bikeback.address.service.AddressSearchClient;
import com.bikeprojectminji.bikeback.address.service.AddressSearchProviderResult;
import com.bikeprojectminji.bikeback.address.service.AddressSearchQuery;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(prefix = "address.search", name = "provider", havingValue = "kakao")
public class KakaoLocalAddressSearchClient implements AddressSearchClient {

    private static final String PROVIDER = "KAKAO_LOCAL";

    private final RestClient restClient;
    private final String restApiKey;

    public KakaoLocalAddressSearchClient(
            @Value("${address.search.kakao.base-url:https://dapi.kakao.com}") String baseUrl,
            @Value("${address.search.kakao.rest-api-key:}") String restApiKey
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.restApiKey = restApiKey;
    }

    @Override
    public AddressSearchProviderResult search(AddressSearchQuery query) {
        if (restApiKey == null || restApiKey.isBlank()) {
            return AddressSearchProviderResult.providerFailure(PROVIDER);
        }
        try {
            KakaoKeywordSearchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/local/search/keyword.json")
                            .queryParam("query", query.rawQuery())
                            .queryParam("page", query.page())
                            .queryParam("size", query.size())
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                    .retrieve()
                    .body(KakaoKeywordSearchResponse.class);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return AddressSearchProviderResult.empty(PROVIDER);
            }
            return AddressSearchProviderResult.success(PROVIDER, response.documents().stream()
                    .map(KakaoDocument::toCandidate)
                    .toList());
        } catch (HttpStatusCodeException exception) {
            if (isRateLimited(exception.getStatusCode())) {
                return AddressSearchProviderResult.rateLimited(PROVIDER);
            }
            return AddressSearchProviderResult.providerFailure(PROVIDER);
        } catch (RestClientException exception) {
            return AddressSearchProviderResult.providerFailure(PROVIDER);
        }
    }

    private boolean isRateLimited(HttpStatusCode statusCode) {
        return statusCode.value() == 429;
    }

    private record KakaoKeywordSearchResponse(List<KakaoDocument> documents) {
    }

    private record KakaoDocument(
            String id,
            String place_name,
            String address_name,
            String road_address_name,
            String x,
            String y,
            String category_group_name
    ) {

        private AddressCandidate toCandidate() {
            return new AddressCandidate(
                    id,
                    firstNonBlank(place_name, road_address_name, address_name, "주소 후보"),
                    firstNonBlank(road_address_name, address_name, null, ""),
                    toDecimal(y),
                    toDecimal(x),
                    PROVIDER,
                    firstNonBlank(category_group_name, "PLACE"),
                    "HIGH"
            );
        }

        private static String firstNonBlank(String first, String fallback) {
            return first == null || first.isBlank() ? fallback : first;
        }

        private static String firstNonBlank(String first, String second, String third, String fallback) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            if (second != null && !second.isBlank()) {
                return second;
            }
            if (third != null && !third.isBlank()) {
                return third;
            }
            return fallback;
        }

        private static BigDecimal toDecimal(String value) {
            if (value == null || value.isBlank()) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(value);
        }
    }
}
