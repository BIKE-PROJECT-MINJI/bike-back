package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GraphHopperReadinessScriptTest {

    @Test
    @DisplayName("AWS k6 wrapper는 backend health 이후 GraphHopper 실제 route readiness를 확인한다")
    void awsWrapperWaitsForGraphHopperRouteReadiness() throws Exception {
        String script = Files.readString(Path.of("ops/loadtest/run-aws-compose-k6.sh"));

        assertThat(script).contains("for candidate in 8080");
        assertThat(script).contains("/health");
        assertThat(script).doesNotContain("status\" == \"401\"");
        assertThat(script).contains("remote-exit-code.txt");
        assertThat(script).contains("scp_from_instance_optional");
        assertThat(script).contains("graphhopper_attempt=");
        assertThat(script).contains("starting_k6");
        assertThat(script).contains("attempt\" == \"$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS");
        assertThat(script).contains("GRAPHHOPPER_CACHE_ARCHIVE_URL");
        assertThat(script).contains("GRAPHHOPPER_CACHE_EXPORT");
        assertThat(script).contains("export_graphhopper_cache");
        assertThat(script).contains("graphhopper-cache.tgz");
        assertThat(script).contains("restore_graphhopper_cache");
        assertThat(script).contains("graphhopper_route_status");
        assertThat(script).contains("/route?profile=bike");
        assertThat(script).contains("graphhopper_route_ready_status=");
    }

    @Test
    @DisplayName("AWS k6 wrapper는 기본적으로 GraphHopper named volume을 지우지 않아 graph-cache를 재사용한다")
    void awsWrapperKeepsGraphHopperCacheVolumeByDefault() throws Exception {
        String script = Files.readString(Path.of("ops/loadtest/run-aws-compose-k6.sh"));

        assertThat(script).contains("RESET_GRAPHHOPPER_CACHE=\"${RESET_GRAPHHOPPER_CACHE:-false}\"");
        assertThat(script).contains("docker compose --env-file .env.test -f docker-compose.test.yml down");
        assertThat(script).contains("docker compose --env-file .env.test -f docker-compose.test.yml down -v");
    }
}
