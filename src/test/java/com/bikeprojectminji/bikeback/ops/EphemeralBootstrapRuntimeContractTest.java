package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class EphemeralBootstrapRuntimeContractTest {

    private static final Path BOOTSTRAP = Path.of("ops/aws/ephemeral-validation/bootstrap");

    @Test
    @DisplayName("앱 bootstrap은 Redis 비밀번호를 URI가 아닌 분리된 configtree 속성으로 전달한다")
    void appUsesSeparateRedisPropertiesInsteadOfPasswordUri() throws Exception {
        String app = Files.readString(BOOTSTRAP.resolve("app.sh"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(app).contains(
                "fetch_secret redis-password spring.data.redis.password",
                "$SECRET_DIR/spring.data.redis.host",
                "$SECRET_DIR/spring.data.redis.port"
        );
        assertThat(app).doesNotContain("spring.data.redis.url", "redis://:");
        assertThat(application).doesNotContain("url: ${SPRING_DATA_REDIS_URL:${REDIS_URL:}}");
    }

    @Test
    @DisplayName("표준 Redis URL 환경변수는 YAML 중계 없이도 Spring 속성으로 유지된다")
    void standardRedisUrlEnvironmentVariableRemainsSupported() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "redis-url-contract",
                Map.of("SPRING_DATA_REDIS_URL", "rediss://default:token@example.invalid:6379")
        ));

        assertThat(environment.getProperty("spring.data.redis.url"))
                .isEqualTo("rediss://default:token@example.invalid:6379");
    }

    @Test
    @DisplayName("GraphHopper bootstrap은 custom model 상대경로를 mount root에서 해석한다")
    void graphHopperUsesMountedAssetDirectoryAsWorkingDirectory() throws Exception {
        String graphHopper = Files.readString(BOOTSTRAP.resolve("graphhopper.sh"));

        assertThat(graphHopper).contains("--workdir /graphhopper");
        assertThat(graphHopper.indexOf("--workdir /graphhopper"))
                .isLessThan(graphHopper.indexOf("\"$GRAPHHOPPER_IMAGE\""));
    }
}
