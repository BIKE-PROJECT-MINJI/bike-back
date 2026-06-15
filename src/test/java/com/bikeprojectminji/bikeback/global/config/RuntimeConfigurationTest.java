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

    @Test
    @DisplayName("코스 생성처럼 insert가 많은 작업을 위해 Hibernate JDBC batching을 설정한다")
    void hibernateJdbcBatchingIsConfiguredForInsertHeavyCourseCreation() {
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size", Integer.class))
                .isEqualTo(50);
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.order_inserts", Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("비동기 실행기는 bounded pool 기본값을 가진다")
    void asyncExecutorDefaultsAreBounded() {
        assertThat(environment.getProperty("bike.async.core-pool-size", Integer.class)).isEqualTo(4);
        assertThat(environment.getProperty("bike.async.max-pool-size", Integer.class)).isEqualTo(8);
        assertThat(environment.getProperty("bike.async.queue-capacity", Integer.class)).isEqualTo(500);
    }
}
