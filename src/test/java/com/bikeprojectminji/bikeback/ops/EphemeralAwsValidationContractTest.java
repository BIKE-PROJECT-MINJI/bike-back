package com.bikeprojectminji.bikeback.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EphemeralAwsValidationContractTest {

    private static final Path STACK = Path.of("ops/aws/ephemeral-validation");

    @Test
    @DisplayName("임시 검증 스택은 no-NAT private node와 기존 ACM HTTPS만 허용한다")
    void stackUsesPrivateNodesAndExistingCertificate() throws Exception {
        String network = read("network.tf") + read("locals.tf");
        String compute = read("compute.tf");
        String edge = read("edge.tf");

        assertThat(network).contains("aws_vpc_endpoint", "Gateway", "ssmmessages", "private_dns_enabled = true");
        assertThat(network).doesNotContain("aws_nat_gateway");
        assertThat(compute).contains("associate_public_ip_address", "= false");
        assertThat(edge).contains(
                "var.existing_acm_certificate_arn",
                "for_each = var.attach_app_targets ? aws_instance.app : {}"
        );
        assertThat(edge).doesNotContain("resource \"aws_acm_certificate\"");
    }

    @Test
    @DisplayName("비밀은 Terraform state와 user-data를 거치지 않고 SSM SecureString 경로로만 읽는다")
    void stackKeepsSecretsOutsideTerraformState() throws Exception {
        String compute = read("compute.tf");
        String iam = read("iam.tf");
        String variables = read("variables.tf");

        assertThat(compute).contains("secret_parameter_prefix", "bootstrap_object_key");
        assertThat(iam).contains("ssm:GetParameter", "ssm:GetParametersByPath", "kms:Decrypt");
        assertThat(variables).doesNotContain("db_password", "redis_password", "api_key");
    }

    @Test
    @DisplayName("비용과 TTL gate는 20퍼센트 여유·3달러·165분 정리·180분 상한을 강제한다")
    void stackEnforcesCostAndCleanupGates() throws Exception {
        String variables = read("variables.tf");
        String compute = read("compute.tf");
        String providers = read("providers.tf");
        String cleanup = read("cleanup.tf");
        String preflight = read("scripts/preflight.sh");
        String artifactBuild = read("scripts/build-and-upload-artifacts.sh");

        assertThat(variables).contains(
                "cost_limit_usd", "default     = 3", "ttl_minutes", "default     = 180",
                "ecs_optimized_ami_id", "lookup(var.root_volume_sizes_gib, role, -1) == 30"
        );
        assertThat(compute).contains(
                "ami                                  = var.ecs_optimized_ami_id",
                "cpu_credits = \"standard\"",
                "local.ecs_ami_root_volume_size_gib"
        );
        assertThat(providers).contains("image-id", "var.ecs_optimized_ami_id");
        assertThat(cleanup).contains("aws_scheduler_schedule", "cleanup_start_at");
        assertThat(preflight).contains(
                "COST_HEADROOM=1.20", "pricing get-products", "165", "180",
                "ROOT_VOLUME_SIZE_GIB=30", "describe-images", "ECS_AMI_ROOT_VOLUME_GIB",
                "root_volume_sizes_gib", "APP_COUNT + 5"
        );
        assertThat(artifactBuild).contains(
                "tfvars.get(\"root_volume_sizes_gib\")",
                "backend worktree must be clean and committed"
        );
        assertThat(artifactBuild).doesNotContain("\"app\": 16", "\"redis\": 8");
    }

    @Test
    @DisplayName("모든 과금 자원은 RunId 태그와 fallback reaper 입력으로 연결된다")
    void stackTagsAndReapsPaidResources() throws Exception {
        String locals = read("locals.tf");
        String cleanup = read("cleanup.tf");
        String cleanupFunction = read("lambda/cleanup.py");

        assertThat(locals).contains("RunId", "var.run_id", "ExpiresAt");
        assertThat(cleanup).contains("RUN_ID", "aws_scheduler_schedule");
        assertThat(cleanupFunction).contains(
                "describe_instances",
                "describe_load_balancers",
                "describe_vpc_endpoints",
                "terminate_instances",
                "delete_load_balancer",
                "delete_vpc_endpoints",
                "s3_bucket",
                "_count_s3_residuals"
        );
    }

    @Test
    @DisplayName("control plane은 S3 artifact보다 cleanup scheduler를 먼저 고정한다")
    void controlPlaneProtectsArtifactsBeforeBucketCreation() throws Exception {
        String controlPlane = read("scripts/prepare-control-plane.sh");
        String bootstrapGate = read("bootstrap-gate.tf");

        assertThat(controlPlane).contains(
                "-target=aws_scheduler_schedule.cleanup",
                "-target=aws_lambda_permission.scheduler",
                "get-schedule",
                "create-bucket",
                "Description",
                "Parameter.Type",
                "existing parameter ownership mismatch",
                "get-bucket-tagging",
                "existing artifact bucket ownership mismatch",
                "ssm put-parameter"
        );
        assertThat(controlPlane).doesNotContain("ssm add-tags-to-resource", "request[\"Overwrite\"]");
        assertThat(controlPlane).doesNotContain("\"Tags\": [");
        assertThat(controlPlane.indexOf("-target=aws_scheduler_schedule.cleanup"))
                .isLessThan(controlPlane.indexOf("create-bucket"));
        assertThat(bootstrapGate).contains(
                "data \"external\" \"bootstrap_prerequisites\"",
                "check-bootstrap-prerequisites.py",
                "resource \"terraform_data\" \"bootstrap_prerequisites\""
        );
    }

    @Test
    @DisplayName("k6 실패 evidence는 attempt 격리, SSM 출력, redaction을 포함한다")
    void k6FailureEvidenceIsIsolatedAndRedacted() throws Exception {
        String runner = read("scripts/run-k6-stage.sh");
        String commandEvidence = read("scripts/ssm-command-evidence.sh");
        String workload = Files.readString(Path.of("ops/loadtest/k6/bike-api.js"));

        assertThat(runner).contains(
                "install -d -m 0770 -o 12345 -g 12345",
                "ATTEMPT_ID",
                "k6.stdout.log",
                "k6.stderr.log",
                "OutputS3BucketName",
                "OutputS3KeyPrefix",
                "scan-k6-evidence-redaction.sh",
                ".artifacts/$RUN_ID/k6/$STAGE",
                "assert-remaining-ttl.sh"
        );
        assertThat(runner).contains(
                "evidence/{test_id}/{attempt_id}/ssm-output",
                "render-k6-evidence-manifest.sh",
                "-e TEST_ID=${ATTEMPT_ID}"
        );
        assertThat(runner).doesNotContain(
                "rm -rf \"$EVIDENCE_DIR\"",
                "rm -rf /opt/gaja-run/evidence/${TEST_ID}",
                "> >(tee"
        );
        assertThat(commandEvidence).contains(
                "get-command-invocation",
                "InvocationDoesNotExist",
                "command-poll-error.txt",
                "Failed | Cancelled | TimedOut"
        );
        assertThat(workload).contains(
                "summaryTrendStats",
                "'p(95)'",
                "'p(99)'"
        );
        assertThat(runner).doesNotContain("aws ssm wait command-executed");
    }

    @Test
    @DisplayName("검증 노드는 실행별 k6와 관측성 evidence 경로에만 쓸 수 있다")
    void validationNodeCanWriteOnlyRunEvidencePrefixes() throws Exception {
        String iam = read("iam.tf");

        assertThat(iam).contains(
                "${local.artifact_prefix}/evidence/*",
                "${local.artifact_prefix}/observability/*"
        );
        assertThat(iam).doesNotContain(
                "arn:${data.aws_partition.current.partition}:s3:::${var.artifact_bucket_name}/*"
        );
    }

    @Test
    @DisplayName("단일 matrix 실행기는 순서를 고정하고 모든 종료 경로에서 삭제 감사를 수행한다")
    void matrixRunnerEnforcesOrderAndCleanup() throws Exception {
        String matrix = read("scripts/run-validation-matrix.sh");

        assertThat(matrix).contains(
                "trap cleanup EXIT INT TERM",
                "preflight.sh",
                "prepare-control-plane.sh",
                "build-and-upload-artifacts.sh",
                "plan-create-only.sh",
                "EXPECTED_BACKEND_IMAGE",
                "verify-bootstrap-and-attach.sh",
                "run_stage smoke",
                "run_stage baseline-10",
                "run_stage stress-25",
                "run_stage ai-25",
                "collect-stage-observability.sh",
                "destroy-and-audit.sh"
        );
        assertThat(matrix.indexOf("run_stage smoke"))
                .isLessThan(matrix.indexOf("run_stage baseline-10"));
        assertThat(matrix.indexOf("run_stage baseline-10"))
                .isLessThan(matrix.indexOf("run_stage stress-25"));
        assertThat(matrix.indexOf("run_stage stress-25"))
                .isLessThan(matrix.indexOf("run_stage ai-25"));
    }

    @Test
    @DisplayName("ALB 상시 health는 의존성 readiness와 분리한다")
    void albUsesLivenessHealthAfterReadinessAttachment() throws Exception {
        String edge = read("edge.tf");
        String gate = read("scripts/verify-bootstrap-and-attach.sh");

        assertThat(edge).contains("path                = \"/health\"");
        assertThat(edge).doesNotContain("path                = \"/ready\"");
        assertThat(gate).contains("http://127.0.0.1:8080/ready");
    }

    @Test
    @DisplayName("관측성 gate는 구성된 앱 수만큼 Prometheus UP target을 요구한다")
    void observabilityRequiresEveryAppTarget() throws Exception {
        String bootstrap = read("bootstrap/observability.sh");
        String gate = read("scripts/verify-bootstrap-and-attach.sh");

        assertThat(bootstrap).contains("EXPECTED_APP_TARGETS", "healthy_targets");
        assertThat(gate).contains(
                "/opt/gaja-run/verify-observability.sh",
                "EXPECTED_APP_TARGETS",
                "healthy_targets",
                ".artifacts/$RUN_ID/runtime-gate"
        );
    }

    @Test
    @DisplayName("runtime gate는 준비 상태를 제한 시간 동안 재확인하고 실패 진단을 보존한다")
    void runtimeGatePollsReadinessAndCapturesDiagnostics() throws Exception {
        String gate = read("scripts/verify-bootstrap-and-attach.sh");
        String manifestRenderer = read("scripts/render-runtime-diagnostics-manifest.sh");
        String redactionScanner = read("scripts/scan-runtime-diagnostics-redaction.sh");

        assertThat(gate).contains(
                "wait_for_runtime_gate",
                "collect_runtime_diagnostics",
                "RUNTIME_GATE_TIMEOUT_SECONDS",
                "RUNTIME_GATE_POLL_SECONDS",
                "RUNTIME_GATE_DEADLINE_EPOCH",
                "runtime_gate_failed",
                "systemctl is-failed --quiet cloud-final.service",
                "cloud-init status --long",
                "journalctl -u cloud-final.service",
                "docker ps -a --no-trunc",
                "docker container inspect --format",
                ".State.OOMKilled",
                "docker logs --since 20m --tail 120",
                "[REDACTED_SECRET]",
                "parallel runtime diagnostics",
                "get-command-invocation",
                "diagnostics-${role}.json",
                "render-runtime-diagnostics-manifest.sh",
                "scan-runtime-diagnostics-redaction.sh",
                "diagnostics-manifest.json",
                "diagnostics-redaction-scan.json",
                "collect_diagnostics 2>&1 | redact_output",
                "X-Amz-",
                "[REDACTED_PARSE_VALUE]",
                "exit 0\nEOF",
                "runtime gate timed out"
        );
        assertThat(manifestRenderer).contains(
                "runtime_gate_status",
                "diagnostic_command_status",
                "potentially_truncated",
                "23_900",
                "7_900"
        );
        assertThat(redactionScanner).contains("invalid_json_files", "parse_exception_value");
        assertThat(gate.indexOf("=== container state ==="))
                .isLessThan(gate.indexOf("=== cloud-final journal ==="));
        assertThat(gate.indexOf("=== local HTTP probes ==="))
                .isLessThan(gate.indexOf("=== cloud-final journal ==="));
        assertThat(gate).doesNotContain(
                "aws ssm wait command-executed",
                "docker inspect",
                ".Config.Env",
                "cat /run/gaja/secrets",
                "printenv"
        );
    }

    @Test
    @DisplayName("ALB target은 SSM 의존성 gate가 모두 통과한 뒤에만 등록한다")
    void targetAttachmentRequiresRuntimeDependencyGate() throws Exception {
        String gate = read("scripts/verify-bootstrap-and-attach.sh");
        String outputs = read("outputs.tf");

        assertThat(gate).contains(
                "aws ssm send-command",
                "/opt/gaja-run/graphhopper.ready",
                "/opt/gaja-run/db.ready",
                "/opt/gaja-run/redis.ready",
                "http://127.0.0.1:8091/health",
                "http://127.0.0.1:8080/ready",
                "attach_app_targets=true",
                "aws elbv2 wait target-in-service"
        );
        assertThat(outputs).contains("output \"instance_ids\"");
    }

    @Test
    @DisplayName("종료는 Terraform destroy 뒤 RunId 잔존 자원 0건을 감사한다")
    void teardownDestroysAndAuditsAllRunResources() throws Exception {
        String teardown = read("scripts/destroy-and-audit.sh");

        assertThat(teardown).contains(
                "terraform -chdir=\"$STACK_DIR\" destroy",
                "describe-instances",
                "describe-volumes",
                "describe-addresses",
                "describe-nat-gateways",
                "describe-vpc-endpoints",
                "describe-subnets",
                "describe-route-tables",
                "describe-internet-gateways",
                "describe-security-groups",
                "describe-network-interfaces",
                "describe-${resource_kind}",
                "load-balancers",
                "target-groups",
                "get-parameters-by-path",
                "list-schedules",
                "list-functions",
                "list-instance-profiles",
                "mapfile -t sorted_resources",
                "final_bucket_state=\"$(bucket_state)\"",
                ".artifacts/$RUN_ID/teardown",
                "residual_total",
                "exit 1"
        );
        assertThat(teardown).doesNotContain(
                "} | tee \"$EVIDENCE_DIR/residual-audit.json\"",
                "--output text 2>/dev/null || printf '0'"
        );
        assertThat(teardown + read("bootstrap-gate.tf")).contains(
                "-var=destroy_mode=true",
                "destroy-authorized",
                "trimspace(file(local.destroy_authorization_file)) == var.run_id",
                "artifact bucket still exists",
                "head-bucket returned unexpected error",
                "'(404)'",
                "'NoSuchBucket'"
        );
        assertThat(teardown.indexOf("aws s3 rm"))
                .isLessThan(teardown.indexOf("terraform -chdir=\"$STACK_DIR\" destroy"));
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(STACK.resolve(relativePath));
    }
}
