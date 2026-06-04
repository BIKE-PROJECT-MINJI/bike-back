package com.bikeprojectminji.bikeback.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RuntimeConfigurationTest {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Ngrok 같은 외부 터널 뒤에서도 forwarded header를 해석하도록 설정한다")
    void forwardedHeadersStrategyDefaultsToFramework() {
        assertThat(environment.getProperty("server.forward-headers-strategy"))
                .isEqualTo("framework");
    }
}
