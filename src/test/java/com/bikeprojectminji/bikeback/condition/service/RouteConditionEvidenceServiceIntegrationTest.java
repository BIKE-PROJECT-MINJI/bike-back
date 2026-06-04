package com.bikeprojectminji.bikeback.condition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        RouteConditionEvidenceService.class,
        RouteConditionEvidenceServiceIntegrationTest.TestConditionConfig.class
})
class RouteConditionEvidenceServiceIntegrationTest {

    private final RouteConditionEvidenceService routeConditionEvidenceService;
    private final ScenarioConditionClient weatherClient;
    private final ScenarioConditionClient dustClient;
    private final ScenarioConditionClient roadworkClient;
    private final ScenarioConditionClient closureClient;
    private final ScenarioConditionClient surfaceClient;

    @Autowired
    RouteConditionEvidenceServiceIntegrationTest(
            RouteConditionEvidenceService routeConditionEvidenceService,
            ScenarioConditionClient weatherClient,
            ScenarioConditionClient dustClient,
            ScenarioConditionClient roadworkClient,
            ScenarioConditionClient closureClient,
            ScenarioConditionClient surfaceClient
    ) {
        this.routeConditionEvidenceService = routeConditionEvidenceService;
        this.weatherClient = weatherClient;
        this.dustClient = dustClient;
        this.roadworkClient = roadworkClient;
        this.closureClient = closureClient;
        this.surfaceClient = surfaceClient;
    }

    @Test
    @DisplayName("조건 provider evidence는 success, unknown, failure를 구분해 반환한다")
    void collectReturnsVerifiedUnknownAndFailedEvidence() {
        weatherClient.nextEvidence(RouteConditionEvidence.verified("weather", "날씨", "맑음, 북서풍 12km/h"));
        dustClient.nextEvidence(RouteConditionEvidence.warning("dust", "미세먼지", "PM2.5 나쁨", "MEDIUM"));
        roadworkClient.nextEvidence(RouteConditionEvidence.unknown("roadwork", "공사", "공사 정보 미확인"));
        closureClient.nextFailure();
        surfaceClient.nextEvidence(RouteConditionEvidence.unknown("surface", "노면", "노면 정보 미확인"));

        RouteConditionReport report = routeConditionEvidenceService.collect(request());

        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(report.startAllowed()).isTrue();
        assertThat(report.unknownCount()).isEqualTo(2);
        assertThat(report.failureCount()).isEqualTo(1);
        assertThat(report.evidence())
                .extracting(RouteConditionEvidence::status)
                .contains("VERIFIED", "WARNING", "UNKNOWN", "FAILED");
        assertThat(report.evidence())
                .extracting(RouteConditionEvidence::source)
                .containsExactly("weather", "dust", "roadwork", "closure", "surface");
    }

    @Test
    @DisplayName("조건 provider evidence가 모두 확인되면 READY 상태를 반환한다")
    void collectReturnsReadyWhenAllEvidenceVerified() {
        weatherClient.nextEvidence(RouteConditionEvidence.verified("weather", "날씨", "맑음"));
        dustClient.nextEvidence(RouteConditionEvidence.verified("dust", "미세먼지", "보통"));
        roadworkClient.nextEvidence(RouteConditionEvidence.verified("roadwork", "공사", "확인된 공사 없음"));
        closureClient.nextEvidence(RouteConditionEvidence.verified("closure", "통제", "확인된 통제 없음"));
        surfaceClient.nextEvidence(RouteConditionEvidence.verified("surface", "노면", "양호"));

        RouteConditionReport report = routeConditionEvidenceService.collect(request());

        assertThat(report.status()).isEqualTo("READY");
        assertThat(report.unknownCount()).isZero();
        assertThat(report.failureCount()).isZero();
    }

    @Test
    @DisplayName("조건 provider 요청은 좌표가 없으면 provider 호출 전에 400 예외를 던진다")
    void collectRejectsMissingCoordinate() {
        RouteConditionRequest request = new RouteConditionRequest(null, BigDecimal.valueOf(126.9780));

        assertThatThrownBy(() -> routeConditionEvidenceService.collect(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("조건 조회 좌표");
    }

    private RouteConditionRequest request() {
        return new RouteConditionRequest(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
    }

    @TestConfiguration
    static class TestConditionConfig {

        @Bean
        ScenarioConditionClient weatherClient() {
            return new ScenarioConditionClient("weather", "날씨");
        }

        @Bean
        ScenarioConditionClient dustClient() {
            return new ScenarioConditionClient("dust", "미세먼지");
        }

        @Bean
        ScenarioConditionClient roadworkClient() {
            return new ScenarioConditionClient("roadwork", "공사");
        }

        @Bean
        ScenarioConditionClient closureClient() {
            return new ScenarioConditionClient("closure", "통제");
        }

        @Bean
        ScenarioConditionClient surfaceClient() {
            return new ScenarioConditionClient("surface", "노면");
        }

        @Bean
        List<RouteConditionClient> routeConditionClients(
                ScenarioConditionClient weatherClient,
                ScenarioConditionClient dustClient,
                ScenarioConditionClient roadworkClient,
                ScenarioConditionClient closureClient,
                ScenarioConditionClient surfaceClient
        ) {
            return List.of(weatherClient, dustClient, roadworkClient, closureClient, surfaceClient);
        }
    }

    static class ScenarioConditionClient implements RouteConditionClient {

        private final String source;
        private final String label;
        private RouteConditionEvidence evidence;
        private boolean failure;

        ScenarioConditionClient(String source, String label) {
            this.source = source;
            this.label = label;
            this.evidence = RouteConditionEvidence.unknown(source, label, label + " 정보 미확인");
        }

        void nextEvidence(RouteConditionEvidence evidence) {
            this.evidence = evidence;
            this.failure = false;
        }

        void nextFailure() {
            this.failure = true;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public RouteConditionEvidence lookup(RouteConditionRequest request) {
            if (failure) {
                throw new IllegalStateException(label + " provider failure");
            }
            return evidence;
        }
    }
}
