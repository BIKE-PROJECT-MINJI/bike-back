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
        assertThat(script).contains("remote-stage.txt");
        assertThat(script).contains("scp_from_instance_optional");
        assertThat(script).contains("graphhopper_attempt=");
        assertThat(script).contains("starting_k6");
        assertThat(script).contains("attempt\" == \"$REMOTE_GRAPHHOPPER_READY_MAX_ATTEMPTS");
        assertThat(script).contains("GRAPHHOPPER_CACHE_ARCHIVE_URL");
        assertThat(script).contains("GRAPHHOPPER_CACHE_EXPORT");
        assertThat(script).contains("export_graphhopper_cache");
        assertThat(script).contains("graphhopper-cache.tgz");
        assertThat(script).contains("run -T --rm --no-deps");
        assertThat(script).contains("< /dev/null");
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

    @Test
    @DisplayName("AWS k6 wrapper는 저비용 smoke 기본값과 instance type allowlist를 강제한다")
    void awsWrapperUsesLowCostDefaultsAndInstanceTypeAllowlist() throws Exception {
        String script = Files.readString(Path.of("ops/loadtest/run-aws-compose-k6.sh"));

        assertThat(script).contains("INSTANCE_TYPE=\"${INSTANCE_TYPE:-t3.small}\"");
        assertThat(script).doesNotContain("INSTANCE_TYPE=\"${INSTANCE_TYPE:-t3.xlarge}\"");
        assertThat(script).contains("validate_instance_type");
        assertThat(script).contains("Large, xlarge, and metal instances are disabled");
        assertThat(script).contains("cannot be re-enabled with ALLOWED_INSTANCE_TYPES");
        assertThat(script).contains("validate_cost_guardrails");
        assertThat(script).contains("ALLOWED_INSTANCE_TYPES=\"${ALLOWED_INSTANCE_TYPES:-t3.micro t3.small}\"");
        assertThat(script).contains("ROOT_VOLUME_SIZE_GB=\"${ROOT_VOLUME_SIZE_GB:-30}\"");
        assertThat(script).contains("MAX_ROOT_VOLUME_SIZE_GB=\"${MAX_ROOT_VOLUME_SIZE_GB:-30}\"");
        assertThat(script).contains("K6_FREE_RIDE_VUS=\"${K6_FREE_RIDE_VUS:-1}\"");
        assertThat(script).contains("K6_COURSE_FOLLOW_VUS=\"${K6_COURSE_FOLLOW_VUS:-1}\"");
        assertThat(script).contains("RUN_BEFORE=\"${RUN_BEFORE:-false}\"");
        assertThat(script).contains("INSTANCE_TTL_SECONDS=\"${INSTANCE_TTL_SECONDS:-1800}\"");
        assertThat(script).contains("MAX_INSTANCE_TTL_SECONDS=\"${MAX_INSTANCE_TTL_SECONDS:-3600}\"");
        assertThat(script).contains("ALLOW_LONG_TTL_AWS_RUN");
        assertThat(script).contains("ALLOW_HIGH_VU_AWS_RUN");
        assertThat(script).contains("MAX_SINGLE_COMPOSE_TOTAL_VUS=\"${MAX_SINGLE_COMPOSE_TOTAL_VUS:-25}\"");
        assertThat(script).contains("MAX_RUN_DURATION_SECONDS=\"${MAX_RUN_DURATION_SECONDS:-900}\"");
        assertThat(script).contains("ALLOW_LONG_DURATION_AWS_RUN");
        assertThat(script).contains("duration_to_seconds");
    }
}
