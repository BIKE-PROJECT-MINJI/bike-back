package com.bikeprojectminji.bikeback.global.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.public-scrape-enabled=true"
})
@ActiveProfiles("test")
class ActuatorPrometheusPublicScrapeIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("private scrape 모드에서는 management port의 Prometheus endpoint를 인증 없이 조회할 수 있다")
    void prometheusEndpointOnManagementPortAllowsPrivateScrapeWhenEnabled() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + managementPort + "/actuator/prometheus",
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("jvm_info");
    }
}
