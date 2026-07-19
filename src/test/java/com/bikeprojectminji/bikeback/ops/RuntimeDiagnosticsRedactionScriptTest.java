package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeDiagnosticsRedactionScriptTest {

    private static final Path SCRIPT = Path.of(
            "ops/aws/ephemeral-validation/scripts/scan-runtime-diagnostics-redaction.sh");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("JSON에 이스케이프된 parse exception 원문은 redaction scan을 실패시킨다")
    void scanFailsForJsonEscapedParseExceptionValue() throws Exception {
        writeDiagnostic("For input string: \"secret-value\"");

        ProcessResult result = runScan();
        JsonNode scan = objectMapper.readTree(tempDir.resolve("scan.json").toFile());

        assertThat(result.exitCode()).withFailMessage(result.output()).isNotZero();
        assertThat(scan.get("pass").asBoolean()).isFalse();
        assertThat(scan.path("matches").path("parse_exception_value").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("정제된 parse exception 값은 redaction scan을 통과한다")
    void scanPassesForRedactedParseExceptionValue() throws Exception {
        writeDiagnostic("For input string: \"[REDACTED_PARSE_VALUE]\"");

        ProcessResult result = runScan();
        JsonNode scan = objectMapper.readTree(tempDir.resolve("scan.json").toFile());

        assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
        assertThat(scan.get("pass").asBoolean()).isTrue();
        assertThat(scan.path("matches").path("parse_exception_value").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("손상된 diagnostics JSON은 민감값이 없어도 redaction scan을 실패시킨다")
    void scanFailsForMalformedDiagnosticJson() throws Exception {
        Files.writeString(tempDir.resolve("diagnostics-app-1.json"), "{\"Status\":\"Success\"");

        ProcessResult result = runScan();
        JsonNode scan = objectMapper.readTree(tempDir.resolve("scan.json").toFile());

        assertThat(result.exitCode()).withFailMessage(result.output()).isNotZero();
        assertThat(scan.get("pass").asBoolean()).isFalse();
        assertThat(scan.path("matches").path("invalid_json").asBoolean()).isTrue();
        assertThat(scan.path("invalid_json_files").get(0).asText()).isEqualTo("diagnostics-app-1.json");
    }

    private void writeDiagnostic(String stdout) throws Exception {
        objectMapper.writeValue(tempDir.resolve("diagnostics-app-1.json").toFile(), Map.of(
                "Status", "Success",
                "StandardOutputContent", stdout,
                "StandardErrorContent", ""));
    }

    private ProcessResult runScan() throws Exception {
        Process process = new ProcessBuilder(
                "bash", SCRIPT.toString(), tempDir.toString(), tempDir.resolve("scan.json").toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private record ProcessResult(int exitCode, String output) {}
}
