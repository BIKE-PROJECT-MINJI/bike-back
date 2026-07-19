package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeDiagnosticsManifestScriptTest {

    private static final Path SCRIPT = Path.of(
            "ops/aws/ephemeral-validation/scripts/render-runtime-diagnostics-manifest.sh");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("진단 manifest는 runtime 상태, 수집 실패, stdout과 stderr 잘림을 구분한다")
    void manifestSeparatesRuntimeAndDiagnosticOutcomes() throws Exception {
        Path instanceIds = tempDir.resolve("instance-ids.json");
        Path manifest = tempDir.resolve("diagnostics-manifest.json");
        Files.writeString(instanceIds, """
                {
                  "app": {"app-1": "i-app"},
                  "singleton": {
                    "db": "i-db",
                    "redis": "i-redis",
                    "ai": "i-ai",
                    "graphhopper": "i-graphhopper",
                    "observability": "i-observability",
                    "load": "i-load"
                  }
                }
                """);
        writeResult("app-1", "Failed");
        writeDiagnostic("app-1", "Success", "container state", "");
        writeDiagnostic("db", "UNAVAILABLE", "", "detail retrieval failed");
        writeDiagnostic("redis", "Failed", "partial output", "command failed");
        writeDiagnostic("ai", "Success", "a".repeat(23_900), "");
        writeDiagnostic("graphhopper", "Success", "", "e".repeat(7_900));
        writeDiagnostic("observability", "InProgress", "partial output", "");
        writeDiagnostic("load", "Success", "", "");

        Process process = new ProcessBuilder(
                "bash", SCRIPT.toString(), tempDir.toString(), instanceIds.toString(), manifest.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).withFailMessage(output).isZero();
        JsonNode root = objectMapper.readTree(manifest.toFile());
        Map<String, JsonNode> byRole = StreamSupport.stream(root.spliterator(), false)
                .collect(Collectors.toMap(node -> node.get("role").asText(), Function.identity()));

        assertThat(byRole).hasSize(7);
        assertThat(byRole.get("app-1").get("capture").asText()).isEqualTo("CAPTURED");
        assertThat(byRole.get("app-1").get("runtime_gate_status").asText()).isEqualTo("Failed");
        assertThat(byRole.get("app-1").get("diagnostic_command_status").asText()).isEqualTo("Success");
        assertThat(byRole.get("db").get("capture").asText()).isEqualTo("UNAVAILABLE");
        assertThat(byRole.get("db").get("runtime_gate_status").asText()).isEqualTo("NOT_RUN");
        assertThat(byRole.get("redis").get("capture").asText()).isEqualTo("PARTIAL");
        assertThat(byRole.get("observability").get("capture").asText()).isEqualTo("PARTIAL");
        assertThat(byRole.get("ai").get("potentially_truncated").asBoolean()).isTrue();
        assertThat(byRole.get("graphhopper").get("potentially_truncated").asBoolean()).isTrue();
        assertThat(byRole.get("load").get("capture").asText()).isEqualTo("UNAVAILABLE");
    }

    private void writeResult(String role, String status) throws Exception {
        objectMapper.writeValue(tempDir.resolve(role + "-result.json").toFile(), Map.of("status", status));
    }

    private void writeDiagnostic(String role, String status, String stdout, String stderr) throws Exception {
        objectMapper.writeValue(tempDir.resolve("diagnostics-" + role + ".json").toFile(), Map.of(
                "Status", status,
                "StandardOutputContent", stdout,
                "StandardErrorContent", stderr));
    }
}
