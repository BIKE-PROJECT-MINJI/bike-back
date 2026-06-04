package com.bikeprojectminji.bikeback.address.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.address.dto.AddressSearchResponse;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(classes = {
        AddressSearchService.class,
        AddressSearchServiceIntegrationTest.TestAddressSearchConfig.class
})
class AddressSearchServiceIntegrationTest {

    private final AddressSearchService addressSearchService;
    private final ScenarioAddressSearchClient addressSearchClient;

    @Autowired
    AddressSearchServiceIntegrationTest(
            AddressSearchService addressSearchService,
            ScenarioAddressSearchClient addressSearchClient
    ) {
        this.addressSearchService = addressSearchService;
        this.addressSearchClient = addressSearchClient;
    }

    @Test
    @DisplayName("주소 검색은 후보가 여러 개면 AMBIGUOUS 상태를 반환한다")
    void searchReturnsAmbiguousWhenMultipleCandidatesExist() {
        addressSearchClient.nextResult(AddressSearchProviderResult.success(
                "FAKE",
                List.of(
                        new AddressCandidate("a1", "북악스카이웨이 팔각정", "서울 종로구 북악산로 267", BigDecimal.valueOf(37.6026), BigDecimal.valueOf(126.9803), "FAKE", "PLACE", "HIGH"),
                        new AddressCandidate("a2", "북악스카이웨이 입구", "서울 성북구 정릉동", BigDecimal.valueOf(37.5921), BigDecimal.valueOf(126.9811), "FAKE", "ADDRESS", "MEDIUM")
                )
        ));

        AddressSearchResponse response = addressSearchService.search("북악스카이웨이", 1, 5);

        assertThat(response.status()).isEqualTo("AMBIGUOUS");
        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.candidates()).hasSize(2);
    }

    @Test
    @DisplayName("주소 검색은 후보가 없으면 EMPTY 상태를 반환한다")
    void searchReturnsEmptyWhenNoCandidateExists() {
        addressSearchClient.nextResult(AddressSearchProviderResult.empty("FAKE"));

        AddressSearchResponse response = addressSearchService.search("없는 장소", 1, 5);

        assertThat(response.status()).isEqualTo("EMPTY");
        assertThat(response.candidates()).isEmpty();
        assertThat(response.message()).contains("없");
    }

    @Test
    @DisplayName("주소 검색은 provider 실패를 PROVIDER_FAILURE 상태로 변환한다")
    void searchReturnsProviderFailureWhenClientFails() {
        addressSearchClient.nextResult(AddressSearchProviderResult.providerFailure("FAKE"));

        AddressSearchResponse response = addressSearchService.search("북악스카이웨이", 1, 5);

        assertThat(response.status()).isEqualTo("PROVIDER_FAILURE");
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    @DisplayName("주소 검색은 provider rate limit을 RATE_LIMITED 상태로 변환한다")
    void searchReturnsRateLimitedWhenProviderRateLimited() {
        addressSearchClient.nextResult(AddressSearchProviderResult.rateLimited("FAKE"));

        AddressSearchResponse response = addressSearchService.search("북악스카이웨이", 1, 5);

        assertThat(response.status()).isEqualTo("RATE_LIMITED");
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    @DisplayName("주소 검색은 raw query가 비어 있으면 provider를 호출하지 않고 400 예외를 던진다")
    void searchRejectsBlankQuery() {
        assertThatThrownBy(() -> addressSearchService.search(" ", 1, 5))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("query");
    }

    @TestConfiguration
    static class TestAddressSearchConfig {

        @Bean
        ScenarioAddressSearchClient scenarioAddressSearchClient() {
            return new ScenarioAddressSearchClient();
        }

        @Bean
        AddressSearchClient addressSearchClient(ScenarioAddressSearchClient client) {
            return client;
        }
    }

    static class ScenarioAddressSearchClient implements AddressSearchClient {

        private AddressSearchProviderResult result = AddressSearchProviderResult.empty("FAKE");

        void nextResult(AddressSearchProviderResult result) {
            this.result = result;
        }

        @Override
        public AddressSearchProviderResult search(AddressSearchQuery query) {
            return result;
        }
    }
}
